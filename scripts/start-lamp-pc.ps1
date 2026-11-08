$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$localRuntimeConfig = Join-Path $repoDirectory 'tmp/local-runtime.json'
if (Test-Path -LiteralPath $localRuntimeConfig) {
    & (Join-Path $PSScriptRoot 'manage-local.ps1') -Action start -Service frontend -ConfigPath $localRuntimeConfig
    return
}
$state = Import-Clixml -LiteralPath (Join-Path $repoDirectory 'tmp/lamp-rehearsal-state.clixml')
$env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:' + $state.ServerPort
$env:VITE_APP_SCOPE = 'lamp-rehearsal'
$env:VITE_APP_TITLE = '灯智造 MES · 演练'
$env:VITE_APP_NAME = '灯智造'
Push-Location (Join-Path $repoDirectory 'workspace/vscode/ems-forword')
try { & npm.cmd run dev -- --host 127.0.0.1 --port $state.FrontendPort --strictPort }
finally { Pop-Location }
