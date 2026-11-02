param([switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$localRuntimeConfig = Join-Path $repoDirectory 'tmp/local-runtime.json'
if (Test-Path -LiteralPath $localRuntimeConfig) {
    $runtimeAction = if ($ValidateOnly) { 'status' } else { 'start' }
    & (Join-Path $PSScriptRoot 'manage-local.ps1') -Action $runtimeAction -Service backend -ConfigPath $localRuntimeConfig
    return
}
$state = Import-Clixml -LiteralPath (Join-Path $repoDirectory 'tmp/lamp-rehearsal-state.clixml')
if ($state.DbUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/mes_lamp_rehearsal\?') {
    throw 'Unexpected rehearsal database. No server was started.'
}
$env:DB_URL = $state.DbUrl
$env:DB_USERNAME = $state.DbCredential.UserName
$env:DB_PASSWORD = $state.DbCredential.GetNetworkCredential().Password
$env:JWT_SECRET = ([pscredential]::new('jwt', $state.JwtSecret)).GetNetworkCredential().Password
$env:SERVER_PORT = [string]$state.ServerPort
$env:CACHE_NAMESPACE = $state.CacheNamespace
if ($ValidateOnly) { 'Lamp rehearsal database, JWT signing key, cache namespace and port loaded.'; exit 0 }
# 查询能力完成台灯适配后再启用 DeepSeek；本阶段不沿用羽毛球上下文回答台灯业务。
& (Join-Path $PSScriptRoot 'start-local-backend.ps1') -DisableAI
