# Uses the archived pre-upgrade classes, not the currently edited source tree.
$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $repoDirectory 'tmp/lamp-baseline-runtime'
$state = Import-Clixml -LiteralPath (Join-Path $repoDirectory 'tmp/local-mysql-state.clixml')
if ($state.DbUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/mes_local\?') { throw 'Unexpected historical database.' }
$env:DB_URL = $state.DbUrl
$env:DB_USERNAME = $state.DbCredential.UserName
$env:DB_PASSWORD = $state.DbCredential.GetNetworkCredential().Password
$env:SERVER_PORT = '8088'
Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:CACHE_NAMESPACE -ErrorAction SilentlyContinue
$dependencies = (Get-Content -Raw -LiteralPath (Join-Path $runtimeDirectory 'dependencies.txt')).Trim()
Push-Location (Join-Path $repoDirectory 'workspace/idea/ems')
try { & java -cp ((Join-Path $runtimeDirectory 'classes') + ';' + $dependencies) com.neuedu.ems.EmsApplication }
finally { Pop-Location }
