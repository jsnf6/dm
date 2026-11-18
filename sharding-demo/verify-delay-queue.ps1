# =============================================================================
# Redisson 延迟队列 + DB 库存最终一致性 端到端验证
#
# 前置: MySQL(3306) 已启动; docker 里 redis 容器名 = redis; kafka(9092) 可用
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File E:\codexop\sharding-demo\verify-delay-queue.ps1
#       默认会把 program_id=1 的 DB 库存重置为 200 再预热, 保证脚本可重复跑;
#       不想动数据就加 -ResetStock 0 (此时要自己保证库存够用)。
#
# 链路: POST /order/order  (Lua 扣 Redis 库存 -> Kafka 异步建单 + 台账 PENDING)
#       POST /order/pay/N  (订单 1->2, 台账 PENDING->PAID, 延迟任务入队)
#       Redisson 延迟队列 (5~15s 抖动) -> 扣 t_stock + 台账 PAID->SUCCESS
#
# 覆盖:
#   [A] 单笔主链路: 支付后 ZCARD +1 -> 15 秒内 ZSET 清空 -> 台账 SUCCESS -> DB 库存正好 -1
#   [B] 只下单不支付: 等 95 秒, DB 库存必须纹丝不动, 台账停在 PENDING   <- 守"未支付不扣库存"
#   [C] 50 并发支付: 不重不漏(50 条 SUCCESS, DB/Redis 库存都正好 -50)
# =============================================================================
param(
    [int]$PortDemo = 18080,
    [long]$ProgramId = 1,
    [int]$Concurrency = 50,
    [int]$ResetStock = 100,
    [string]$RedisContainer = "redis"
)
$ErrorActionPreference = "Stop"

$demoJar   = "E:\javademo\demo\target\demo-0.0.1-SNAPSHOT.jar"
$mysqlExe  = "E:\mysql\mysql-8.0.34-winx64\bin\mysql.exe"
$logDir    = "E:\codexop\sharding-demo\logs"
$demoLog   = Join-Path $logDir "demo-delay.log"
$queueName = "stock:sync:delay"
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

# 原生命令(docker/mysql)会往 stderr 写警告, 而 $ErrorActionPreference=Stop 会把它们变成终止错误,
# 所以统一临时降级为 Continue; mysql 的密码走 MYSQL_PWD, 避免 "Using a password ... insecure" 警告。
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
        # 静默失败最坑: 比如 UPDATE 撞了 CHECK 约束, 不看 stderr 根本发现不了
        if ($err -and ($err -notmatch "Using a password")) { throw ("mysql 报错: " + $err.Trim() + " ; SQL: " + $sql) }
        return (($out | Out-String).Trim())
    } finally {
        $ErrorActionPreference = $old
        Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
    }
}
function Db-Stock() { return [int](Mysql-Scalar "SELECT stock FROM stock_db.t_stock WHERE program_id=$ProgramId") }
function Ledger-Status([long]$orderNumber) { return Mysql-Scalar "SELECT status FROM stock_db.t_stock_deduct_log WHERE order_number=$orderNumber" }

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

# 注意: ConvertFrom-Json 在返回 JSON 数组时, 是把整个数组当成"一个对象"吐进管道的,
# 所以 @( ... | ConvertFrom-Json ) 会多包一层(Count 恒为 1), 后面取属性就退化成数组了。
# 这里显式区分"数组/单对象"两种情况。
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
# 下单走 Kafka 异步建单, 订单不会立刻可见; 这里轮询到出现比 $since 更大的订单号为止
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

$userIdA = 900001
$userIdB = 900002
$userIdC = 910000
$procDemo = $null

try {
    Write-Host "== 启动 demo ==" -ForegroundColor Cyan
    $procDemo = Start-Process $java -ArgumentList "-jar", $demoJar, ("--server.port=" + $PortDemo) `
        -RedirectStandardOutput $demoLog `
        -RedirectStandardError  (Join-Path $logDir "demo-delay.err.log") -WindowStyle Hidden -PassThru
    if (-not (Wait-Http ($baseUrl + "/order/listByUserId/1") 180)) {
        throw ("demo 没起来, 看 " + $demoLog)
    }
    Write-Host "demo 就绪" -ForegroundColor Green

    Write-Host ""
    Write-Host "== 重置并预热库存 ==" -ForegroundColor Cyan
    if ($ResetStock -gt 0) {
        # t_stock 上有 CHECK(stock <= total_stock), 直接写大值会被约束拒绝, 所以取 LEAST
        $null = Mysql-Scalar ("UPDATE stock_db.t_stock SET stock=LEAST(total_stock,$ResetStock) WHERE program_id=$ProgramId")
        Info ("已把 t_stock(program_id=" + $ProgramId + ") 的可售库存重置为 min(total_stock, " + $ResetStock + ") (加 -ResetStock 0 可跳过)")
    }
    $null = Invoke-Demo "POST" ("/program/prewarm/" + $ProgramId) ""
    $dbStockStart = Db-Stock
    $redisStart   = Redis-Int "GET" @($stockKey)
    Info ("DB stock = " + $dbStockStart + " ; Redis " + $stockKey + " = " + $redisStart)
    Check "预热后 Redis 计数器 = DB 库存" $redisStart $dbStockStart
    Info ("延迟队列 zset = " + $zsetKey + " , 当前 ZCARD = " + (Redis-Int "ZCARD" @($zsetKey)))

    # ---------------------------------------------------------------- [A]
    Write-Host ""
    Write-Host "[A] 单笔主链路: 下单 -> 支付 -> 延迟落库" -ForegroundColor Cyan
    $sinceA = Get-MaxOrderNumber $userIdA
    $bodyA = "{""userId"":$userIdA,""programId"":$ProgramId,""buyCount"":1}"
    $rA = Invoke-Demo "POST" "/order/order" $bodyA
    Check "下单 -> 200" $rA.status 200
    Check "下单后 Redis 计数器 -1 (入口即扣)" (Redis-Int "GET" @($stockKey)) ($redisStart - 1)

    $orderA = Wait-OrderNumber $userIdA $sinceA 30
    Check "Kafka 异步建单成功(订单可见)" ($null -ne $orderA) $true
    Info ("orderNumber = " + $orderA)
    Check "此时台账 = PENDING (还没支付)" (Ledger-Status $orderA) "PENDING"
    Check "此时 DB 库存还没动 (延迟任务未到点)" (Db-Stock) $dbStockStart

    $zsetBefore = Redis-Int "ZCARD" @($zsetKey)
    $rPayA = Invoke-Demo "POST" ("/order/pay/" + $orderA) ""
    Check "支付 -> true" $rPayA.body "true"
    Check "支付后台账 = PAID" (Ledger-Status $orderA) "PAID"
    Check "延迟任务已入队 (ZCARD +1)" (Redis-Int "ZCARD" @($zsetKey)) ($zsetBefore + 1)

    $stA = Wait-LedgerStatus $orderA "SUCCESS" 40
    Check "15 秒左右任务到点, 台账 -> SUCCESS" $stA "SUCCESS"
    Check "ZSET 已清空" (Redis-Int "ZCARD" @($zsetKey)) $zsetBefore
    Check "DB 库存正好 -1" (Db-Stock) ($dbStockStart - 1)
    Check "Redis 与 DB 两侧一致" (Redis-Int "GET" @($stockKey)) (Db-Stock)

    # ---------------------------------------------------------------- [B]
    Write-Host ""
    Write-Host "[B] 只下单不支付: 补偿任务不能给未支付订单扣库存 (等 95 秒)" -ForegroundColor Cyan
    $sinceB = Get-MaxOrderNumber $userIdB
    $bodyB = "{""userId"":$userIdB,""programId"":$ProgramId,""buyCount"":1}"
    $rB = Invoke-Demo "POST" "/order/order" $bodyB
    Check "下单 -> 200" $rB.status 200
    $orderB = Wait-OrderNumber $userIdB $sinceB 30
    Check "Kafka 异步建单成功(订单可见)" ($null -ne $orderB) $true
    Info ("orderNumber = " + $orderB + " , 故意不调 /order/pay")
    $dbStockBeforeB = Db-Stock
    Info "等待 95 秒: 补偿任务每 30 秒扫一次, 只捞 create_time 超过 60 秒的台账 ..."
    for ($i = 0; $i -lt 19; $i++) { Start-Sleep -Seconds 5; Write-Host "." -NoNewline }
    Write-Host ""
    Check "台账仍然停在 PENDING" (Ledger-Status $orderB) "PENDING"
    Check "DB 库存纹丝不动 (没有被误扣)" (Db-Stock) $dbStockBeforeB
    # demo 的日志是 GBK(平台默认编码)写的, 用 -Encoding Default 才读得出中文
    $ev = (Get-Content -Encoding Default $demoLog | Select-String -Pattern "补偿任务" | Select-Object -Last 4 | ForEach-Object { $_.Line.Trim() }) -join " | "
    Info ("补偿任务日志证据: " + $ev)
    Check "补偿任务扫到该台账但没扣库存" ([bool]($ev -like "*未支付不扣库存*")) $true

    # ---------------------------------------------------------------- [C]
    Write-Host ""
    Write-Host ("[C] {0} 并发下单 + 支付: 验证不重不漏" -f $Concurrency) -ForegroundColor Cyan
    $dbStockBeforeC = Db-Stock
    $redisBeforeC   = Redis-Int "GET" @($stockKey)

    [System.Net.ServicePointManager]::DefaultConnectionLimit = 512
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $pending = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $Concurrency; $i++) {
        $uid  = $userIdC + $i
        $body = "{""userId"":$uid,""programId"":$ProgramId,""buyCount"":1}"
        $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, "application/json")
        $null = $pending.Add(@{ user = $uid; task = $client.PostAsync(($baseUrl + "/order/order"), $content) })
    }
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($pending | ForEach-Object { $_.task }))
    $okCount = 0
    foreach ($p in $pending) { if ([int]$p.task.Result.StatusCode -eq 200) { $okCount++ } }
    $client.Dispose()
    Check ("{0} 个并发下单请求全部 200" -f $Concurrency) $okCount $Concurrency
    Check "Redis 计数器正好 -N" (Redis-Int "GET" @($stockKey)) ($redisBeforeC - $Concurrency)

    $ordersC = @()
    foreach ($p in $pending) {
        $on = Wait-OrderNumber $p.user 0 60
        if ($null -ne $on) { $ordersC += $on }
    }
    Check ("Kafka 建单 {0} 条, 一条不丢" -f $Concurrency) $ordersC.Count $Concurrency

    $paid = 0
    foreach ($on in $ordersC) {
        if ((Invoke-Demo "POST" ("/order/pay/" + $on) "").body -eq "true") { $paid++ }
    }
    Check ("{0} 笔支付全部成功" -f $Concurrency) $paid $Concurrency

    $inList = ($ordersC -join ",")
    $deadline = (Get-Date).AddSeconds(180)
    $succ = -1
    while ((Get-Date) -lt $deadline) {
        $succ = [int](Mysql-Scalar ("SELECT COUNT(*) FROM stock_db.t_stock_deduct_log WHERE status='SUCCESS' AND order_number IN (" + $inList + ")"))
        if ($succ -eq $Concurrency) { break }
        Start-Sleep -Seconds 2
    }
    Check ("台账 SUCCESS 正好 {0} 条 (不多不少)" -f $Concurrency) $succ $Concurrency
    Check "DB 库存正好 -N (没有重复扣)" (Db-Stock) ($dbStockBeforeC - $Concurrency)
    # 场景 B 那笔未支付订单在 Redis 侧已经扣过、DB 侧没扣, 两边天然差 1。
    # 正确的口径是: DB 库存 = Redis 计数器 + 未支付订单占用的库存
    $heldByUnpaid = $dbStockBeforeC - $redisBeforeC
    Check "Redis + 未支付占用 = DB (口径一致)" (Redis-Int "GET" @($stockKey)) ((Db-Stock) - $heldByUnpaid)
    Check "ZSET 最终清空" (Redis-Int "ZCARD" @($zsetKey)) 0
    Check "没有 FAILED 台账" ([int](Mysql-Scalar ("SELECT COUNT(*) FROM stock_db.t_stock_deduct_log WHERE status='FAILED' AND order_number IN (" + $inList + ")"))) 0

    Write-Host ""
    Write-Host "== Redis 现场 ==" -ForegroundColor Cyan
    Redis-Cli "KEYS" @("*stock:sync:delay*") | ForEach-Object { "    " + $_ }
    Info ($stockKey + " = " + (Redis-Int "GET" @($stockKey)))
    Write-Host "== DB 现场 ==" -ForegroundColor Cyan
    Info ("t_stock: program_id=" + $ProgramId + " stock=" + (Db-Stock) + " (基线 " + $dbStockStart + ")")
    Mysql-Scalar ("SELECT status, COUNT(*) FROM stock_db.t_stock_deduct_log WHERE order_number IN (" + $inList + "," + $orderA + "," + $orderB + ") GROUP BY status") |
        ForEach-Object { "    " + $_ }
}
finally {
    if ($procDemo -and -not $procDemo.HasExited) { Stop-Process -Id $procDemo.Id -Force }
    Write-Host ""
    Write-Host ("demo 已停止 (日志: " + $demoLog + ")")
}

Write-Host ""
Write-Host ("结果: 通过 {0} 项, 失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }