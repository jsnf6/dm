# =============================================================================
# 网关 Redis ZSET 三维防刷 端到端验证
#
# 前置: MySQL(3306)/Redis(6379)/Kafka(9092) 已启动, docker 里 redis 容器名 = redis
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File E:\codexop\sharding-demo\verify-gateway-limit.ps1
#
# 生效规则(application.yaml 的 gateway.limit.rules):
#   /order/order  threshold=5  statTime=10  effectiveTime=30   超了封禁 30 秒
#   /order/**     threshold=30 statTime=10  effectiveTime=0    只限流不封禁
#
# 覆盖: 5 次放行 -> 第 6 次 429 -> 封禁 key 落 Redis -> 封禁期不记账 -> 封禁期仍拒
#        -> 换 userId 放行 -> 未命中规则的接口不受影响
#
# 注意: demo 的 /order/order 上有 @RepeatExecuteLimit(keys={userId,programId}, durationTime=5),
#       同一个 userId 在 5 秒内重复下单会被它挡成 500. 那是 demo 的业务幂等, 不是网关限流,
#       所以下面 [1] 只断言网关放行(非 429), 只有第 1 次会真的端到端 200.
# =============================================================================
param(
    [int]$PortDemo = 18080,
    [int]$PortGateway = 18888,
    [long]$ProgramId = 1,
    [string]$RedisContainer = "redis"
)
$ErrorActionPreference = "Stop"

$demoJar   = "E:\javademo\demo\target\demo-0.0.1-SNAPSHOT.jar"
$gwJar     = "E:\javademo\demo-gateway\target\demo-gateway-0.0.1-SNAPSHOT.jar"
$gwClasses = "E:\javademo\demo-gateway\target\classes"
$keyFile   = "E:\codexop\sharding-demo\client-private-key.txt"
$logDir    = "E:\codexop\sharding-demo\logs"
$java      = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$hash      = [string][char]35
$eq        = [string][char]61

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
foreach ($f in @($demoJar, $gwJar, $keyFile)) { if (-not (Test-Path $f)) { throw ("找不到 " + $f) } }

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

function Redis-Cli([string]$cmd, [string[]]$keys) {
    $all = @($cmd) + $keys
    $out = & docker exec $RedisContainer redis-cli @all
    return (($out | Out-String).Trim())
}

function Redis-Int([string]$cmd, [string[]]$keys) {
    $v = Redis-Cli $cmd $keys
    if (-not $v) { return -1 }
    return [int]$v
}

# body 走临时文件传给 java: PowerShell 5.1 直接传字符串会把 JSON 双引号吃掉
function New-SignedHeaders([string]$body) {
    $tmp = Join-Path $env:TEMP ("sign-body-" + [guid]::NewGuid().ToString("N") + ".json")
    [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $out = & $java -cp $gwClasses com.example.gateway.util.SignCli $keyFile "client-app" ("@" + $tmp)
        $headers = @{}
        foreach ($line in $out) {
            if ($line.StartsWith($hash)) { continue }
            $idx = $line.IndexOf($eq)
            $headers[$line.Substring(0, $idx)] = $line.Substring($idx + 1)
        }
        return $headers
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Gateway([string]$method, [string]$path, $headers, [string]$body) {
    $uri = "http://127.0.0.1:" + $PortGateway + $path
    $p = @{ Uri = $uri; Method = $method; TimeoutSec = 20; UseBasicParsing = $true }
    if ($headers -and $headers.Count -gt 0) { $p["Headers"] = $headers }
    if ($body) { $p["Body"] = $body; $p["ContentType"] = "application/json" }
    try {
        $r = Invoke-WebRequest @p
        return @{ status = [int]$r.StatusCode; body = $r.Content; retryAfter = [string]$r.Headers["Retry-After"] }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $content = $reader.ReadToEnd()
            return @{ status = [int]$resp.StatusCode; body = $content; retryAfter = [string]$resp.Headers["Retry-After"] }
        }
        return @{ status = -1; body = $_.Exception.Message; retryAfter = "" }
    }
}

function Wait-Http([string]$uri, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $uri -TimeoutSec 3 -UseBasicParsing
            if ([int]$r.StatusCode -eq 200) { return $true }
        } catch {
            if ($_.Exception.Response -and ([int]$_.Exception.Response.StatusCode) -eq 200) { return $true }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

$procDemo = $null
$procGw = $null
$uid1 = 910000 + (Get-Random -Minimum 1 -Maximum 80000)
$uid2 = $uid1 + 1
$statKey = "api:limit:/order/order:10:" + $uid1
$banKey  = "api:limit:ban:/order/order:10:" + $uid1

try {
    Write-Host ("== 启动 demo(:{0}) 和 gateway(:{1})" -f $PortDemo, $PortGateway) -ForegroundColor Cyan
    $procDemo = Start-Process $java -ArgumentList "-jar", $demoJar, ("--server.port=" + $PortDemo) -RedirectStandardOutput (Join-Path $logDir "demo.log") -RedirectStandardError (Join-Path $logDir "demo.err.log") -WindowStyle Hidden -PassThru
    if (-not (Wait-Http ("http://127.0.0.1:" + $PortDemo + "/program/getByProgramId?programId=" + $ProgramId) 180)) {
        throw ("demo 没起来, 看 " + (Join-Path $logDir "demo.log"))
    }
    Write-Host "demo 就绪" -ForegroundColor Green

    $procGw = Start-Process $java -ArgumentList "-jar", $gwJar, ("--server.port=" + $PortGateway) -RedirectStandardOutput (Join-Path $logDir "gateway.log") -RedirectStandardError (Join-Path $logDir "gateway.err.log") -WindowStyle Hidden -PassThru
    if (-not (Wait-Http ("http://127.0.0.1:" + $PortGateway + "/actuator/health") 180)) {
        throw ("gateway 没起来, 看 " + (Join-Path $logDir "gateway.log"))
    }
    Write-Host "gateway 就绪" -ForegroundColor Green

    $null = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $PortDemo + "/program/prewarm/" + $ProgramId) -Method Post -TimeoutSec 20 -UseBasicParsing

    Write-Host ""
    Write-Host "== 清理测试 key, 保证从 0 开始 ==" -ForegroundColor Cyan
    $null = Redis-Cli "DEL" @($statKey, $banKey)

    $body1 = @{ userId = $uid1; programId = $ProgramId; buyCount = 1 } | ConvertTo-Json -Compress
    Write-Host ("   测试 body: " + $body1)

    Write-Host ""
    Write-Host "[1] 同一 userId 连打 5 次, 网关应该全部放行" -ForegroundColor Cyan
    $statuses = @()
    for ($i = 1; $i -le 5; $i++) {
        $h = New-SignedHeaders $body1
        $r = Invoke-Gateway "POST" "/order/order" $h $body1
        $statuses += $r.status
        Check ("第 " + $i + " 次 -> 网关放行(非 429)") ($r.status -ne 429) $true
    }
    Write-Host ("     5 次 status = " + ($statuses -join ", ") + "  (第 2 次起被 demo 自己的幂等注解挡成 500, 与本练习无关)")
    Check "第 1 次端到端 200" $statuses[0] 200
    Check "5 次都记进了 ZSET" (Redis-Int "ZCARD" @($statKey)) 5

    Write-Host ""
    Write-Host "[2] 第 6 次应该被拒" -ForegroundColor Cyan
    $h6 = New-SignedHeaders $body1
    $r6 = Invoke-Gateway "POST" "/order/order" $h6 $body1
    Write-Host ("     响应: " + $r6.body)
    Check "第 6 次 -> 429" $r6.status 429
    Check "Retry-After = 30" $r6.retryAfter "30"

    Write-Host ""
    Write-Host "[3] 封禁 key 真的写进了 Redis" -ForegroundColor Cyan
    $exists = Redis-Int "EXISTS" @($banKey)
    $ttl    = Redis-Int "TTL" @($banKey)
    Write-Host ("     " + $banKey + "  EXISTS=" + $exists + "  TTL=" + $ttl)
    Check "封禁 key 存在" $exists 1
    Check "封禁 TTL 在 (0,30]" (($ttl -gt 0) -and ($ttl -le 30)) $true

    Write-Host ""
    Write-Host "[4] 封禁期内不能再往 ZSET 记账" -ForegroundColor Cyan
    Check "ZSET 基数 = 5" (Redis-Int "ZCARD" @($statKey)) 5

    Write-Host ""
    Write-Host "[5] 封禁期内换新 nonce 继续打, 仍然拒绝" -ForegroundColor Cyan
    $h7 = New-SignedHeaders $body1
    $r7 = Invoke-Gateway "POST" "/order/order" $h7 $body1
    Check "封禁期内 -> 429" $r7.status 429
    Check "封禁期内 ZSET 基数仍是 5" (Redis-Int "ZCARD" @($statKey)) 5

    Write-Host ""
    Write-Host "[6] 换一个 userId, 桶应该是独立的" -ForegroundColor Cyan
    $body2 = @{ userId = $uid2; programId = $ProgramId; buyCount = 1 } | ConvertTo-Json -Compress
    $h8 = New-SignedHeaders $body2
    $r8 = Invoke-Gateway "POST" "/order/order" $h8 $body2
    Write-Host ("     响应: status=" + $r8.status)
    Check "另一个 userId -> 200" $r8.status 200

    Write-Host ""
    Write-Host "[7] 未命中限流规则的接口不受影响" -ForegroundColor Cyan
    Check "actuator/health -> 200" (Invoke-Gateway "GET" "/actuator/health" @{} "").status 200

    Write-Host ""
    Write-Host ("== 原始响应证据(curl -i), 已被封禁的 userId=" + $uid1 + " ==") -ForegroundColor Cyan
    $tmp = Join-Path $env:TEMP ("limit-body-" + [guid]::NewGuid().ToString("N") + ".json")
    [System.IO.File]::WriteAllText($tmp, $body1, (New-Object System.Text.UTF8Encoding($false)))
    $hr = New-SignedHeaders $body1
    $curlArgs = @("-s", "-i", "-X", "POST", ("http://127.0.0.1:" + $PortGateway + "/order/order"), "-H", "Content-Type: application/json", "--data-binary", ("@" + $tmp))
    foreach ($k in $hr.Keys) { $curlArgs += @("-H", ($k + ": " + $hr[$k])) }
    & curl.exe @curlArgs | Where-Object { $_ -notmatch "^\s*$" } | ForEach-Object { "  " + $_ }
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue

    Write-Host ""
    Write-Host "== Redis 现场 ==" -ForegroundColor Cyan
    Write-Host ("  ZSET " + $statKey + "  (member=now-seq, score=now 秒)")
    Redis-Cli "ZRANGE" @($statKey, "0", "-1", "WITHSCORES") | ForEach-Object { "    " + $_ }
    Write-Host ("  ban  " + $banKey + "  TTL=" + (Redis-Int "TTL" @($banKey)))
}
finally {
    $null = Redis-Cli "DEL" @($statKey, $banKey)
    foreach ($p in @($procGw, $procDemo)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    }
    Write-Host ""
    Write-Host ("两个进程已停止 (日志: " + $logDir + ")")
}

Write-Host ""
Write-Host ("结果: 通过 {0} 项, 失败 {1} 项" -f $script:pass, $script:fail) -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }