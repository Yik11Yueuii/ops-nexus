param([string]$RedisDir = 'D:\Redis-x64-3.2.100_20250721_130816')
$ErrorActionPreference = 'Stop'
$server = Join-Path $RedisDir 'redis-server.exe'
$client = Join-Path $RedisDir 'redis-cli.exe'
$config = Join-Path $RedisDir 'redis.windows.conf'
foreach ($file in @($server,$client,$config)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Redis 文件不存在：$file" }
}
$listening = Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Start-Process -FilePath $server -ArgumentList ('"'+$config+'"') -WorkingDirectory $RedisDir -WindowStyle Hidden | Out-Null
    $deadline = (Get-Date).AddSeconds(8)
    do {
        Start-Sleep -Milliseconds 250
        $pong = & $client -h 127.0.0.1 -p 6379 PING 2>$null
    } while ($pong -ne 'PONG' -and (Get-Date) -lt $deadline)
} else {
    $pong = & $client -h 127.0.0.1 -p 6379 PING 2>$null
}
if ($pong -ne 'PONG') { throw 'Redis 已启动但 PING 验证失败' }
Write-Host 'Redis 已就绪：127.0.0.1:6379 PONG' -ForegroundColor Green
