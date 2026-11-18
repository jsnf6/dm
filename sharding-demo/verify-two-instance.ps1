# =============================================================================
# 双实例验证: Redis Stream 广播 -> 另一个实例的 Caffeine L1 立刻失效
#
# 前置: MySQL(3306)、Redis(6379) 已启动; 先在 E:\javademo\demo 执行 mvn -DskipTests package
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File E:\codexop\sharding-demo\verify-two-instance.ps1
#
# 步骤:
#   1) 启两个实例 A/B, 在 B 上读一次(把 B 的 L1 填上)
#   2) 负向对照: 绕过应用直连 MySQL 改 total_stock, 不发广播
#      -> B 应该仍然读到旧值, 证明此刻 B 的 L1 还活着(没到 10s TTL),
#         否则第 3 步读到新值就分不清是"广播生效"还是"缓存自然过期"
#   3) 正向验证: 在 A 上调 /program/update(内部发广播) -> B 应该立刻读到新值
#   4) 还原 DB 与缓存
# 说明: 打印读取耗时。L1 命中是亚毫秒级; 一旦 L1 被清掉, 这次读穿透到 MySQL,
#       耗时会明显变大 —— 这也是"确实走了回源"的旁证。
# =============================================================================
param(
    [int]$PortA = 18085,
    [int]$PortB = 18086,
    [long]$ProgramId = 1,
    [int]$NewTotalStock = 108,
    [int]$ControlTotalStock = 105,
    [int]$SettleMs = 500
)
$ErrorActionPreference = 'Stop'

$jar   = 'E:\javademo\demo\target\demo-0.0.1-SNAPSHOT.jar'
$dir   = 'E:\javademo\demo\target'
$mysql = 'E:\mysql\mysql-8.0.34-winx64\bin\mysql.exe'
# 用 MYSQL_PWD 传密码, 避免 mysql.exe 往 stderr 打 'Using a password' 警告
# (PowerShell 5.1 在 $ErrorActionPreference='Stop' 下会把原生 stderr 当成终止性错误)
$env:MYSQL_PWD = '123456'
$java  = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

if (-not (Test-Path $jar)) { throw "找不到 $jar, 请先在 E:\javademo\demo 执行: mvn -DskipTests package" }

function Invoke-Sql([string]$sql) {
    $out = & $mysql --host=127.0.0.1 --port=3306 --user=root --default-character-set=utf8mb4 -N -B -e $sql 2>$null
    if ($LASTEXITCODE -ne 0) { throw "MySQL 执行失败: $sql" }
    return @($out)
}

function Get-Program([int]$port) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/program/getByProgramId?programId={1}" -f $port, $ProgramId) -TimeoutSec 20
    $sw.Stop()
    $script:lastCostMs = $sw.ElapsedMilliseconds
    return $r
}

function Wait-Instance([int]$port, [int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/program/getByProgramId?programId={1}" -f $port, $ProgramId) -TimeoutSec 3
            return $true
        } catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}

$procA = $null
$procB = $null
$failed = $false
$origTotal = 0
$origStock = 0
try {
    $row = @(Invoke-Sql "SELECT CONCAT(total_stock, ' ', stock) FROM stock_db.t_stock WHERE program_id = $ProgramId;")[0]
    $parts = $row.Trim().Split(' ')
    $origTotal = [int]$parts[0]
    $origStock = [int]$parts[1]
    Write-Host "DB 初始值: total_stock=$origTotal, stock=$origStock" -ForegroundColor Cyan

    # 清掉残留的 L2 与 stream, 保证 B 的第一次读一定穿透到 DB
    $null = & docker exec redis redis-cli DEL ("program:" + $ProgramId) 2>$null
    $null = & docker exec redis redis-cli DEL 'stream:program:cache:refresh' 2>$null

    Write-Host "== 启动实例 A(:$PortA) 和 B(:$PortB)" -ForegroundColor Cyan
    $procA = Start-Process $java -ArgumentList '-jar', $jar, "--server.port=$PortA" `
                -RedirectStandardOutput "$dir\instance-a.log" -RedirectStandardError "$dir\instance-a.err.log" `
                -WindowStyle Hidden -PassThru
    $procB = Start-Process $java -ArgumentList '-jar', $jar, "--server.port=$PortB" `
                -RedirectStandardOutput "$dir\instance-b.log" -RedirectStandardError "$dir\instance-b.err.log" `
                -WindowStyle Hidden -PassThru

    if (-not (Wait-Instance $PortA)) { throw "实例 A(:$PortA) 180s 内没起来, 看 $dir\instance-a.log" }
    Write-Host "实例 A(:$PortA) 就绪" -ForegroundColor Green
    if (-not (Wait-Instance $PortB)) { throw "实例 B(:$PortB) 180s 内没起来, 看 $dir\instance-b.log" }
    Write-Host "实例 B(:$PortB) 就绪" -ForegroundColor Green

    # ---- 1) B 读一次: 把 B 的 L1 填上 ----
    Start-Sleep -Seconds 1
    $warm = Get-Program $PortB
    Write-Host ("B 第一次读: totalStock={0} (耗时 {1}ms, 冷启动回源)" -f $warm.totalStock, $script:lastCostMs)

    # ---- 2) 负向对照: 绕过应用直连 DB 改数据, 不发广播 ----
    $null = Invoke-Sql "UPDATE stock_db.t_stock SET total_stock = $ControlTotalStock WHERE program_id = $ProgramId;"
    Start-Sleep -Milliseconds $SettleMs
    $control = Get-Program $PortB
    Write-Host ("[负向对照] DB 已被直连改成 {0}, B 读到 totalStock={1} (耗时 {2}ms)" -f $ControlTotalStock, $control.totalStock, $script:lastCostMs)
    if ($control.totalStock -eq $origTotal) {
        Write-Host "  对照通过: B 仍读到旧值, 说明它此刻命中的是 L1(还没到 10s TTL)" -ForegroundColor Green
    } else {
        $failed = $true
        Write-Host "  对照失败: B 读到了 {0}, 说明 L1 已经失效, 后面的验证不成立" -f $control.totalStock -ForegroundColor Red
    }
    $null = Invoke-Sql "UPDATE stock_db.t_stock SET total_stock = $origTotal WHERE program_id = $ProgramId;"

    # ---- 3) 正向验证: 在 A 上改数据, 由 A 发广播 ----
    $body = @{ programId = $ProgramId; totalStock = $NewTotalStock; stock = $origStock } | ConvertTo-Json
    $null = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/program/update" -f $PortA) -Method Post -ContentType 'application/json' -Body $body
    Write-Host ("[正向] 已在 A(:{0}) 上把 totalStock 改成 {1}, 广播已发出" -f $PortA, $NewTotalStock)

    Start-Sleep -Milliseconds $SettleMs
    $after = Get-Program $PortB
    Write-Host ("B 再次读: totalStock={0} (耗时 {1}ms)" -f $after.totalStock, $script:lastCostMs)
    if ($after.totalStock -eq $NewTotalStock) {
        Write-Host "  正向通过: B 的 L1 被广播清掉, 读到的是 DB 里的新值" -ForegroundColor Green
    } else {
        $failed = $true
        Write-Host ("  正向失败: B 读到 {0}, 期望 {1} —— 广播没生效, B 还在吃自己的 L1" -f $after.totalStock, $NewTotalStock) -ForegroundColor Red
    }
}
finally {
    # ---- 4) 还原 ----
    try {
        if ($origTotal -gt 0) {
            $null = Invoke-Sql "UPDATE stock_db.t_stock SET total_stock = $origTotal, stock = $origStock WHERE program_id = $ProgramId;"
            $null = & docker exec redis redis-cli DEL ("program:" + $ProgramId) 2>$null
            Write-Host ("已还原 DB: total_stock={0}, stock={1} (并清掉 L2)" -f $origTotal, $origStock)
        }
    } catch { Write-Host "还原失败, 请手动检查: $_" -ForegroundColor Red }

    foreach ($p in @($procA, $procB)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    }
    Write-Host "两个实例已停止 (日志: $dir\instance-a.log / instance-b.log)"
}

if ($failed) { Write-Host "RESULT: FAIL" -ForegroundColor Red; exit 1 }
Write-Host "RESULT: PASS —— 跨实例 L1 失效广播生效" -ForegroundColor Green