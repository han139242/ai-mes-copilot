param(
    [ValidateSet('start','stop','restart','status','health','build')][string]$Action = 'status',
    [ValidateSet('all','backend','frontend','database')][string]$Service = 'all',
    [string]$ConfigPath,
    [switch]$DisableAI
)
$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $repoDirectory 'tmp/local-runtime.json' }
if (-not (Test-Path -LiteralPath $ConfigPath)) { throw 'Missing tmp/local-runtime.json. Select a verified business database before starting.' }
$runtimeConfig = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($runtimeConfig.environmentScript) {
    if (-not (Test-Path -LiteralPath $runtimeConfig.environmentScript)) { throw 'Configured private environment script is missing.' }
    . $runtimeConfig.environmentScript
}
$env:DB_URL = 'jdbc:mysql://127.0.0.1:' + $runtimeConfig.database.port + '/' + $runtimeConfig.database.name + '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:SERVER_PORT = [string]$runtimeConfig.backend.port
$env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:' + $runtimeConfig.backend.port
if ($runtimeConfig.cacheNamespace) { $env:CACHE_NAMESPACE = $runtimeConfig.cacheNamespace }
if ($runtimeConfig.runtimeDirectory) { $env:MES_RUNTIME_DIRECTORY = $runtimeConfig.runtimeDirectory }
if ($DisableAI) { $env:AI_ENABLED = 'false'; Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue }
# The supervisor is launched outside this console's process tree, so it receives this
# environment through a snapshot file instead of inherited variables.
& node (Join-Path $PSScriptRoot 'local-runtime.mjs') $Action $Service $ConfigPath
if ($LASTEXITCODE -ne 0) { throw "Runtime operation failed (exit $LASTEXITCODE). No unrelated process was stopped." }
