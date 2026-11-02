$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$snapshot = Join-Path $repoDirectory 'tmp/lamp-baseline-runtime/pc-dist'
if (-not (Test-Path -LiteralPath (Join-Path $snapshot 'index.html'))) { throw 'Historical PC snapshot is missing.' }
$env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:8088'
Push-Location (Join-Path $repoDirectory 'workspace/vscode/ems-forword')
try { & node node_modules/vite/bin/vite.js preview --outDir $snapshot --host 127.0.0.1 --port 5173 --strictPort }
finally { Pop-Location }
