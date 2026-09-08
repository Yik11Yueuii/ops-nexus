param([string]$JavaHome = 'E:\jdk\jdk21')
$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot 'local-env.ps1'
if (Test-Path -LiteralPath $localEnv) { . $localEnv }
if (!(Test-Path "$JavaHome\bin\java.exe")) { throw '请通过 -JavaHome 指定 Java 21 路径' }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
Set-Location (Split-Path $PSScriptRoot -Parent)
mvn -f backend/pom.xml spring-boot:run
exit $LASTEXITCODE
