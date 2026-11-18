# =============================================================================
# 延迟队列"超时未支付关单 + Redis 库存回滚" 端到端验证
#
# 前置: MySQL(3306)/Redis(6379)/Kafka(9092) 已启动; docker 里 redis 容器名 = redis
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File E:\codexop\sharding-demo\verify-order-close.ps1
#
# 关单超时由 application.yaml 的 order.close.timeout-seconds 控制, 本脚本启动 demo 时显式传这个参数,
# 实际到点时间 = 30 秒(+0~5 秒抖动),
# 所以每条关单断言大约要等 30~40 秒。补偿任务阈值是 60 秒, 不会抢在延迟队列前面。
#
# 覆盖:
#   [A] 超时未支付: 延迟任务到点 -> order_status 1->3, 台账 CANCELLED, Redis 库存回滚, DB 库存不动
#   [B] 支付后不能被关单: pay 成功 -> 越过关单到点时刻后 order_status 仍是 2, 台账 SUCCESS, DB 库存 -1
#   [C] 竞态: 关单到点前后持续打 pay -> 最终状态只能是 2 或 3, 且 Redis/DB/台账口径自洽
# =============================================================================
param(
    [int]$PortDemo = 18080,
    [long]$ProgramId = 1,
    [int]$ResetStock = 100,
    [int]$CloseTimeoutSeconds = 30,
    [string]$RedisContainer = "redis"
)
$ErrorActionPreference = "Stop"

$demoJar   = "E:\javademo\demo\target\demo-0.0.1-SNAPSHOT.jar"
$mysqlExe  = "E:\mysql\mysql-8.0.34-winx64\bin\mysql.exe"
$logDir    = "E:\codexop\sharding-demo\logs"
$demoLog   = Join-Path $logDir "demo-close.log"
$queueName = "order:close:delay"
$zsetKey   = "redisson_delay_queue_timeout:{" + $queueName + "}"
$stockKey  = "stock:program:" + $ProgramId
$java      = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$baseUrl   = "http://127.0.0.1:" + $PortDemo

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
foreach ($f in @($demoJar, $mysqlExe)) { if (-not (Test-Path $f)) { throw ("找不到 " + $f) } }

$script:pass = 0
$script:fail = 0

function Check([string]$name, $actual, $expected) {
    if ($actual -eq $expected) {
        $script:pass++
        Write-Host ("  PASS  {0} (得到 {1})" -f $name, $actual) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  FAIL  {0}: 期望 {1}, 实际 {2}" -f $name, $expected, $actual) -ForegroundColor Red
    }
}
function Info([string]$msg) { Write-Host ("     " + $msg) -ForegroundColor DarkGray }

function Invoke-Native([scriptblock]$sb) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return (& $sb 2>$null) } finally { $ErrorActionPreference = $old }
}
function Redis-Cli([string]$cmd, [string[]]$keys) {
    $all = @($cmd) + $keys
    $out = Invoke-Native { docker exec $RedisContainer redis-cli @all }
    return (($out | Out-String).Trim())
}
function Redis-Int([string]$cmd, [string[]]$keys) {
    $v = Redis-Cli $cmd $keys
    if (-not $v) { return -1 }
    return [int]$v
}
function Mysql-Scalar([string]$sql) {
    $env:MYSQL_PWD = "123456"
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        $out = & $mysqlExe "--host=127.0.0.1" "--port=3306" "--user=root" "--default-character-set=utf8mb4" "-N" "-B" "-e" $sql 2>$errFile
        $err = (Get-Content -LiteralPath $errFile -Raw -ErrorAction SilentlyContinue)
        if ($err -and ($err -notmatch "Using a password")) { throw ("mysql 报错: " + $err.Trim() + " ; SQL: " + $sql) }
        return (($out | Out-String).Trim())
    } finally {
        $ErrorActionPreference = $old
        Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
    }
}
function Db-Stock() { return [int](Mysql-Scalar "SELECT stock FROM stock_db.t_stock WHERE program_id=$ProgramId") }
function Ledger-Status([long]$orderNumber) { return Mysql-Scalar "SELECT status FROM stock_db.t_stock_deduct_log WHERE order_number=$orderNumber" }
function Redis-Stock() { return (Redis-Int "GET" @($stockKey)) }

function Wait-Http([string]$uri, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $uri -TimeoutSec 3 -UseBasicParsing
            if ([int]$r.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    return $false
}
function Invoke-Demo([string]$method, [string]$path, [string]$body) {
    $p = @{ Uri = ($baseUrl + $path); Method = $method; TimeoutSec = 30; UseBasicParsing = $true }
    if ($body) { $p["Body"] = $body; $p["ContentType"] = "application/json" }
    try {
        $r = Invoke-WebRequest @p
        return @{ status = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            return @{ status = [int]$resp.StatusCode; body = $reader.ReadToEnd() }
        }
        return @{ status = -1; body = $_.Exception.Message }
    }
}
# 注意: ConvertFrom-Json 返回数组时会把整个数组当成一个对象吐进管道, @() 会多包一层
function Get-UserOrders([long]$userId) {
    $r = Invoke-WebRequest -Uri ($baseUrl + "/order/listByUserId/" + $userId) -TimeoutSec 5 -UseBasicParsing
    $parsed = $r.Content | ConvertFrom-Json
    if ($null -eq $parsed) { return @() }
    if ($parsed -is [System.Array]) { return @($parsed) }
    return @($parsed)
}
function Get-MaxOrderNumber([long]$userId) {
    $list = @(Get-UserOrders $userId)
    if ($list.Count -eq 0) { return [long]0 }
    return [long](($list | Sort-Object { [long]$_.orderNumber } | Select-Object -Last 1).orderNumber)
}
function Get-OrderStatus([long]$orderNumber, [long]$userId) {
    $list = @(Get-UserOrders $userId)
    $hit = @($list | Where-Object { [long]$_.orderNumber -eq $orderNumber })
    if ($hit.Count -eq 0) { return -1 }
    return [int]$hit[0].orderStatus
}
function Wait-OrderNumber([long]$userId, [long]$since, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $m = Get-MaxOrderNumber $userId
            if ($m -gt $since) { return $m }
        } catch { }
        Start-Sleep -Milliseconds 300
    }
    return $null
}
# 轮询订单状态直到等于 want, 返回最后一次读到的状态
function Wait-OrderStatus([long]$orderNumber, [long]$userId, [int]$want, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $last = -1
    while ((Get-Date) -lt $deadline) {
        $last = Get-OrderStatus $orderNumber $userId
        if ($last -eq $want) { return $last }
        Start-Sleep -Milliseconds 500
    }
    return $last
}
function Wait-LedgerStatus([long]$orderNumber, [string]$want, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    $last = ""
    while ((Get-Date) -lt $deadline) {
        $last = Ledger-Status $orderNumber
        if ($last -eq $want) { return $want }
        Start-Sleep -Milliseconds 500
    }
    return $last
}

$userIdA = 930001
$userIdB = 930002
$userIdC = 930003
$procDemo = $null

try {
    Write-Host "== 启动 demo ==" -ForegroundColor Cyan
    $procDemo = Start-Process $java -ArgumentList "-jar", $demoJar, ("--server.port=" + $PortDemo), ("--order.close.timeout-seconds=" + $CloseTimeoutSeconds) `
        -RedirectStandardOutput $demoLog `
        -RedirectStandardError  (Join-Path $logDir "demo-close.err.log") -WindowStyle Hidden -PassThru
    if (-not (Wait-Http ($baseUrl + "/order/listByUserId/1") 180)) { throw ("demo 没起来, 看 " + $demoLog) }
    Write-Host "demo 就绪" -ForegroundColor Green

    Write-Host ""
    Write-Host "== 重置并预热库存 ==" -ForegroundColor Cyan
    if ($ResetStock -gt 0) {
        $null = Mysql-Scalar ("UPDATE stock_db.t_stock SET stock=LEAST(total_stock,$ResetStock) WHERE program_id=$ProgramId")
    }
    $null = Invoke-Demo "POST" ("/program/prewarm/" + $ProgramId) ""
    $dbStart = Db-Stock
    $redisStart = Redis-Stock
    Info ("DB stock = " + $dbStart + " ; Redis " + $stockKey + " = " + $redisStart)
    Check "预热后 Redis 计数器 = DB 库存" $redisStart $dbStart

    # ---------------------------------------------------------------- [A]
    Write-Host ""
    Write-Host "[A] 超时未支付 -> 关单 + Redis 库存回滚" -ForegroundColor Cyan
    $sinceA = Get-MaxOrderNumber $userIdA
    $rA = Invoke-Demo "POST" "/order/order" ("{""userId"":$userIdA,""programId"":$ProgramId,""buyCount"":1}")
    Check "下单 -> 200" $rA.status 200
    $orderA = Wait-OrderNumber $userIdA $sinceA 30
    Check "订单已创建" ($null -ne $orderA) $true
    Info ("orderNumber = " + $orderA)
    Check "初始订单状态 = 1 (未支付)" (Get-OrderStatus $orderA $userIdA) 1
    Check "初始台账 = PENDING" (Ledger-Status $orderA) "PENDING"
    Check "Redis 库存已扣 (入口即扣)" (Redis-Stock) ($redisStart - 1)
    Info "等待关单到点(30~35 秒) ..."
    $stA = Wait-OrderStatus $orderA $userIdA 3 90
    Check "订单被关单: order_status -> 3" $stA 3
    Check "台账 -> CANCELLED" (Wait-LedgerStatus $orderA "CANCELLED" 10) "CANCELLED"
    Check "Redis 库存已回滚到基线" (Redis-Stock) $redisStart
    Check "DB 库存未被扣 (未支付不落库)" (Db-Stock) $dbStart

    # ---------------------------------------------------------------- [B]
    Write-Host ""
    Write-Host "[B] 支付后不能被关单" -ForegroundColor Cyan
    # 每个场景各自记基线: 复用 [A] 的基线会让 [A] 一失败就把后面全带崩(连环误报)
    $dbB = Db-Stock
    $redisB = Redis-Stock
    $sinceB = Get-MaxOrderNumber $userIdB
    $rB = Invoke-Demo "POST" "/order/order" ("{""userId"":$userIdB,""programId"":$ProgramId,""buyCount"":1}")
    Check "下单 -> 200" $rB.status 200
    $orderB = Wait-OrderNumber $userIdB $sinceB 30
    Check "订单已创建" ($null -ne $orderB) $true
    Info ("orderNumber = " + $orderB)
    $rPayB = Invoke-Demo "POST" ("/order/pay/" + $orderB) ""
    Check "支付 -> true" $rPayB.body "true"
    Check "支付后订单状态 = 2" (Get-OrderStatus $orderB $userIdB) 2
    Info "等待越过关单到点时刻(50 秒) ..."
    Start-Sleep -Seconds 50
    Check "越过关单时刻后订单仍是 2 (没被误关)" (Get-OrderStatus $orderB $userIdB) 2
    Check "台账 -> SUCCESS (库存正常落库)" (Wait-LedgerStatus $orderB "SUCCESS" 20) "SUCCESS"
    Check "DB 库存 -1" (Db-Stock) ($dbB - 1)
    Check "Redis 库存 -1 (没有被回滚)" (Redis-Stock) ($redisB - 1)

    # ---------------------------------------------------------------- [C]
    Write-Host ""
    Write-Host "[C] 竞态: 关单到点前后持续打 pay" -ForegroundColor Cyan
    $dbC0 = Db-Stock
    $redisC0 = Redis-Stock
    $sinceC = Get-MaxOrderNumber $userIdC
    $rC = Invoke-Demo "POST" "/order/order" ("{""userId"":$userIdC,""programId"":$ProgramId,""buyCount"":1}")
    Check "下单 -> 200" $rC.status 200
    $orderC = Wait-OrderNumber $userIdC $sinceC 30
    Check "订单已创建" ($null -ne $orderC) $true
    Info ("orderNumber = " + $orderC + " , 从第 30 秒起每 0.2 秒打一次 pay, 持续 10 秒")
    Start-Sleep -Seconds 30
    $payTrue = 0; $payFalse = 0; $payErr = 0
    for ($i = 0; $i -lt 50; $i++) {
        $rp = Invoke-Demo "POST" ("/order/pay/" + $orderC) ""
        if ($rp.body -eq "true") { $payTrue++ } elseif ($rp.body -eq "false") { $payFalse++ } else { $payErr++ }
        Start-Sleep -Milliseconds 200
    }
    Info ("pay 返回: true=" + $payTrue + " , false=" + $payFalse + " , 异常=" + $payErr)
    Check "pay 最多只有一次返回 true" ([bool]($payTrue -le 1)) $true
    $stC = Wait-OrderStatus $orderC $userIdC 2 10
    if ($stC -ne 2) { $stC = Wait-OrderStatus $orderC $userIdC 3 10 }
    Info ("最终订单状态 = " + $stC)
    Check "最终状态只能是 2(已支付) 或 3(已取消)" ([bool](($stC -eq 2) -or ($stC -eq 3))) $true
    Start-Sleep -Seconds 20
    $stC = Get-OrderStatus $orderC $userIdC
    $ledgerC = Ledger-Status $orderC
    $dbC = Db-Stock
    $redisC = Redis-Stock
    Info ("落定后: order_status=" + $stC + " , 台账=" + $ledgerC + " , DB=" + $dbC + " , Redis=" + $redisC)
    if ($stC -eq 2) {
        Check "已支付分支: 台账 SUCCESS" $ledgerC "SUCCESS"
        Check "已支付分支: DB 库存 -1" $dbC ($dbC0 - 1)
        Check "已支付分支: Redis 库存 -1" $redisC ($redisC0 - 1)
    } else {
        Check "已取消分支: 台账 CANCELLED" $ledgerC "CANCELLED"
        Check "已取消分支: DB 库存不动" $dbC $dbC0
        Check "已取消分支: Redis 库存已回滚" $redisC $redisC0
    }

    Write-Host ""
    Write-Host "== 日志取证 ==" -ForegroundColor Cyan
    $ev = (Get-Content -Encoding Default $demoLog |
            Select-String -Pattern "关单|闸门|OrderCloseConsumer|处理任务异常|NullPointer" |
            Select-Object -Last 12 | ForEach-Object { $_.Line.Trim() }) -join " | "
    Info $ev
    Write-Host "== Redis 现场 ==" -ForegroundColor Cyan
    Info ("延迟队列 key: " + (Redis-Cli "KEYS" @("{*order:close:delay*}")))
    Info ("ZCARD " + $zsetKey + " = " + (Redis-Int "ZCARD" @($zsetKey)))
    Info ("Redis 库存 = " + $redisC + " , DB 库存 = " + $dbC)
}
finally {
    if ($procDemo -and -not $procDemo.HasExited) { Stop-Process -Id $procDemo.Id -Force }
    Write-Host ""
    Write-Host ("demo 已停止 (日志: " + $demoLog + ")")
}

Write-Host ""
Write-Host ("结果: 通过 {0} 项, 失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }