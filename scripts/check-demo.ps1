param(
    [string]$BackendUrl = 'http://127.0.0.1:8080',
    [string]$FrontendUrl = 'http://127.0.0.1:5173'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
$applicationConfig = Join-Path $projectRoot 'backend\src\main\resources\application.yml'
if (-not $env:DEMO_ADMIN_PASSWORD -and (Test-Path -LiteralPath $applicationConfig)) {
    $configText = Get-Content -LiteralPath $applicationConfig -Raw
    $defaultPassword = [regex]::Match($configText,'DEMO_ADMIN_PASSWORD:([^}]+)').Groups[1].Value
    if ($defaultPassword) { $env:DEMO_ADMIN_PASSWORD = $defaultPassword }
}
$failures = [System.Collections.Generic.List[string]]::new()
function Result([string]$Name,[bool]$Ok,[string]$Detail) {
    $mark = if ($Ok) { '[PASS]' } else { '[FAIL]' }
    $color = if ($Ok) { 'Green' } else { 'Red' }
    Write-Host "$mark $Name - $Detail" -ForegroundColor $color
    if (-not $Ok) { $failures.Add($Name) }
}
function Warn([string]$Name,[string]$Detail) { Write-Host "[WARN] $Name - $Detail" -ForegroundColor Yellow }

Write-Host 'OpsNexus 演示环境预检（不会输出密钥）' -ForegroundColor Cyan
$java = Join-Path 'E:\jdk\jdk21' 'bin\java.exe'
Result 'Java 21' (Test-Path -LiteralPath $java) 'E:\jdk\jdk21'
Result 'Maven' ($null -ne (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) 'mvn.cmd'
Result 'Node.js' ($null -ne (Get-Command node.exe -ErrorAction SilentlyContinue)) 'node.exe'
Result '本地环境文件' (Test-Path -LiteralPath $localEnv) 'scripts/local-env.ps1'

try { $health = Invoke-RestMethod -Uri "$BackendUrl/api/health" -TimeoutSec 5; Result '后端服务' ($health.data.application -eq 'UP') $BackendUrl }
catch { Result '后端服务' $false '请先运行 .\scripts\start-backend.ps1' }
try { $front = Invoke-WebRequest -Uri $FrontendUrl -TimeoutSec 5 -UseBasicParsing; Result '前端服务' ($front.StatusCode -eq 200) $FrontendUrl }
catch { Result '前端服务' $false '请在 frontend 目录运行 npm.cmd run dev' }

if ($env:DEMO_ADMIN_PASSWORD) {
    try {
        $body = @{username='admin';password=$env:DEMO_ADMIN_PASSWORD} | ConvertTo-Json
        $login = Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 8
        $headers = @{Authorization='Bearer '+$login.data.token}
        Result '管理员登录' ($null -ne $login.data.token) 'admin'
        $status = Invoke-RestMethod -Uri "$BackendUrl/api/admin/status" -Headers $headers -TimeoutSec 8
        Result 'H2 数据库' ($status.data.database -eq 'UP') $status.data.database
        if ($status.data.redis -eq 'UP') { Result 'Redis' $true 'UP' } else { Warn 'Redis' '不可用；核心演示仍可运行，但应在汇报前启动本机 Redis' }
        Result 'DeepSeek 配置' ([bool]$status.data.chatConfigured) '仅检查配置，不消耗调用'
        Result '百炼 Embedding 配置' ([bool]$status.data.embeddingConfigured) '仅检查配置，不消耗调用'
        $dashboard = Invoke-RestMethod -Uri "$BackendUrl/api/dashboard" -Headers $headers -TimeoutSec 8
        $hasKnowledge = [int]$dashboard.data.stats.publishedDocuments -gt 0
        Result '已发布演示知识' $hasKnowledge ("{0} 份" -f $dashboard.data.stats.publishedDocuments)
        $services = Invoke-RestMethod -Uri "$BackendUrl/api/assistant/diagnosis/services" -Headers $headers -TimeoutSec 8
        $hasOrder = @($services.data | Where-Object { $_.SERVICE_NAME -eq 'order-service' }).Count -gt 0
        Result 'order-service 演示数据' $hasOrder '服务目录可查询'
    } catch { Result '受保护接口检查' $false $_.Exception.Message }
} else { Result '管理员密码环境变量' $false 'DEMO_ADMIN_PASSWORD 未加载' }

if ($failures.Count -gt 0) {
    Write-Host ("预检失败：{0} 项。请处理后重试。" -f $failures.Count) -ForegroundColor Red
    exit 1
}
Write-Host '预检通过：可以开始演示。' -ForegroundColor Green
