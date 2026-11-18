# =============================================================================
# 网关 RSA 验签端到端验证
#
# 前置: MySQL(3306)/Redis(6379)/Kafka(9092) 已启动; 两个 jar 都已 package
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File E:\codexop\sharding-demo\verify-gateway-sign.ps1
#
# 覆盖 4 条链路:
#   1) 不带签名头            -> 403
#   2) 签名正确              -> 200, 并且真的进了业务(异步建单链路)
#   3) 签名正确但 body 被改  -> 403(防篡改)
#   4) 同一个 nonce 发两次   -> 第一次 200, 第二次 403(防重放)
# =============================================================================
param(
    [int]$PortDemo = 18080,
    [int]$PortGateway = 18888,
    [long]$ProgramId = 1
)
$ErrorActionPreference = 'Stop'

$demoJar   = 'E:\javademo\demo\target\demo-0.0.1-SNAPSHOT.jar'
$gwJar     = 'E:\javademo\demo-gateway\target\demo-gateway-0.0.1-SNAPSHOT.jar'
$gwClasses = 'E:\javademo\demo-gateway\target\classes'
$keyFile   = 'E:\codexop\sharding-demo\client-private-key.txt'
$logDir    = 'E:\codexop\sharding-demo\logs'
$java      = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
foreach ($f in @($demoJar, $gwJar, $keyFile)) { if (-not (Test-Path $f)) { throw "找不到 $f" } }

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

# body 走临时文件传给 java: PowerShell 5.1 直接传字符串会把 JSON 双引号吃掉
function New-SignedHeaders([string]$body) {
    $tmp = Join-Path $env:TEMP ("sign-body-" + [guid]::NewGuid().ToString('N') + ".json")
    [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $out = & $java -cp $gwClasses com.example.gateway.util.SignCli $keyFile 'client-app' "@$tmp"
        $headers = @{}
        foreach ($line in $out) {
            if ($line.StartsWith('#')) { continue }
            $idx = $line.IndexOf('=')
            $headers[$line.Substring(0, $idx)] = $line.Substring($idx + 1)
        }
        return $headers
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Show-RawResponse([string]$label, $headers, [string]$body) {
    $tmp = Join-Path $env:TEMP ("raw-body-" + [guid]::NewGuid().ToString('N') + ".json")
    [System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))
    $curlArgs = @('-s', '-i', '-X', 'POST', "http://127.0.0.1:$PortGateway/order/order",
                  '-H', 'Content-Type: application/json', '--data-binary', "@$tmp")
    foreach ($k in $headers.Keys) { $curlArgs += @('-H', ($k + ': ' + $headers[$k])) }
    try {
        Write-Host ("  --- " + $label + " ---")
        & curl.exe @curlArgs | Where-Object { $_ -notmatch '^$' } | ForEach-Object { "  " + $_ }
    }
    finally {
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Gateway([string]$method, [string]$path, $headers, [string]$body) {
    $uri = "http://127.0.0.1:$PortGateway$path"
    try {
        $r = Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -Body $body `
                -ContentType 'application/json' -TimeoutSec 20 -UseBasicParsing
        return @{ status = [int]$r.StatusCode; body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $content = $reader.ReadToEnd()
            return @{ status = [int]$resp.StatusCode; body = $content }
        }
        return @{ status = -1; body = $_.Exception.Message }
    }
}

function Wait-Http([string]$uri, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { $null = Invoke-WebRequest -Uri $uri -TimeoutSec 3 -UseBasicParsing; return $true } catch {
            if ($_.Exception.Response) { return $true }
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

$procDemo = $null
$procGw = $null
try {
    Write-Host "== 启动 demo(:$PortDemo) 和 gateway(:$PortGateway)" -ForegroundColor Cyan
    $procDemo = Start-Process $java -ArgumentList '-jar', $demoJar, "--server.port=$PortDemo" `
                    -RedirectStandardOutput "$logDir\demo.log" -RedirectStandardError "$logDir\demo.err.log" `
                    -WindowStyle Hidden -PassThru
    if (-not (Wait-Http "http://127.0.0.1:$PortDemo/program/getByProgramId?programId=$ProgramId" 180)) {
        throw "demo 没起来, 看 $logDir\demo.log"
    }
    Write-Host "demo 就绪" -ForegroundColor Green

    $procGw = Start-Process $java -ArgumentList '-jar', $gwJar, "--server.port=$PortGateway" `
                    -RedirectStandardOutput "$logDir\gateway.log" -RedirectStandardError "$logDir\gateway.err.log" `
                    -WindowStyle Hidden -PassThru
    if (-not (Wait-Http "http://127.0.0.1:$PortGateway/actuator/health" 120)) {
        throw "gateway 没起来, 看 $logDir\gateway.log"
    }
    Write-Host "gateway 就绪" -ForegroundColor Green

    $null = Invoke-WebRequest -Uri "http://127.0.0.1:$PortDemo/program/prewarm/$ProgramId" -Method Post -TimeoutSec 20 -UseBasicParsing
    Write-Host "已预热库存计数器: programId=$ProgramId"

    $orderBody = "{`"userId`":999001,`"programId`":$ProgramId,`"buyCount`":1}"

    Write-Host "`n[1] 不带签名头, 应该被网关拦下"
    $r1 = Invoke-Gateway 'POST' '/order/order' @{} $orderBody
    Write-Host ("     响应: " + $r1.body)
    Check '无签名 -> 403' $r1.status 403

    Write-Host "`n[2] 正确签名, 应该放行并真的进业务"
    $headers2 = New-SignedHeaders $orderBody
    Write-Host ("     签的 body 长度=" + $orderBody.Length)
    $r2 = Invoke-Gateway 'POST' '/order/order' $headers2 $orderBody
    Write-Host ("     响应: status=" + $r2.status + " body=" + $r2.body)
    Check '签名正确 -> 200' $r2.status 200

    Write-Host "`n[3] 签名是用 bodyA 签的, 实际发送 bodyB(篡改 buyCount)"
    $bodyA = "{`"userId`":999002,`"programId`":$ProgramId,`"buyCount`":1}"
    $bodyB = "{`"userId`":999002,`"programId`":$ProgramId,`"buyCount`":99}"
    $headers3 = New-SignedHeaders $bodyA
    $r3 = Invoke-Gateway 'POST' '/order/order' $headers3 $bodyB
    Write-Host ("     响应: " + $r3.body)
    Check 'body 被篡改 -> 403' $r3.status 403

    Write-Host "`n[4] 同一组请求头发两次(重放)"
    $body4 = "{`"userId`":999003,`"programId`":$ProgramId,`"buyCount`":1}"
    $headers4 = New-SignedHeaders $body4
    $r4a = Invoke-Gateway 'POST' '/order/order' $headers4 $body4
    $r4b = Invoke-Gateway 'POST' '/order/order' $headers4 $body4
    Write-Host ("     第二次响应: " + $r4b.body)
    Check '第一次 -> 200' $r4a.status 200
    Check '重放同 nonce -> 403' $r4b.status 403

    Write-Host "`n== 原始响应体证据(curl) ==" -ForegroundColor Cyan
    Show-RawResponse '无签名' @{} $orderBody
    $tamperedBody = "{`"userId`":999002,`"programId`":$ProgramId,`"buyCount`":99}"
    Show-RawResponse '签名对不上 body' (New-SignedHeaders $bodyA) $tamperedBody

    Write-Host "`n== 网关日志里的验签记录 ==" -ForegroundColor Cyan
    $hits = @()
    foreach ($enc in @('UTF8', 'Default')) {
        $hits = Get-Content "$logDir\gateway.log" -Encoding $enc -ErrorAction SilentlyContinue |
                    Select-String -Pattern 'SignValidationFilter' | Select-Object -Last 8
        if ($hits) { break }
    }
    if ($hits) { $hits | ForEach-Object { "  " + $_.Line } } else { Write-Host "  (没匹配到日志行)" }
}
finally {
    foreach ($p in @($procGw, $procDemo)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    }
    Write-Host "`n两个进程已停止 (日志: $logDir)"
}

Write-Host ("`n结果: 通过 {0} 项, 失败 {1} 项" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }