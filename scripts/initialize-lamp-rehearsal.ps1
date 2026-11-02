param([pscredential]$Administrator, [pscredential]$LoginCredential)
$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $repoDirectory 'tmp/lamp-rehearsal-state.clixml'
Push-Location $repoDirectory
try {
    if (-not (Test-Path -LiteralPath $statePath)) {
        if ($null -eq $Administrator) { throw 'Supply -Administrator with a local MySQL administrator credential.' }
        $sourceStatePath = Join-Path $repoDirectory 'tmp/local-mysql-state.clixml'
        if ($null -eq $LoginCredential -and (Test-Path -LiteralPath $sourceStatePath)) {
            $LoginCredential = (Import-Clixml -LiteralPath $sourceStatePath).LoginCredential
        }
        if ($null -eq $LoginCredential) { throw 'Supply -LoginCredential for the application account copied from mes_local.' }
        $env:MYSQL_PWD = $Administrator.GetNetworkCredential().Password
        $env:MYSQL_ADMIN_USER = $Administrator.UserName
        $prepared = & python scripts/prepare-lamp-rehearsal.py
        if ($LASTEXITCODE -ne 0) { throw 'Rehearsal preparation failed.' }
        $prepared = $prepared | ConvertFrom-Json
        [pscustomobject]@{
            DbUrl = 'jdbc:mysql://127.0.0.1:3306/mes_lamp_rehearsal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
            DbCredential = [pscredential]::new($prepared.username,(ConvertTo-SecureString $prepared.password -AsPlainText -Force))
            JwtSecret = ConvertTo-SecureString $prepared.jwt_secret -AsPlainText -Force
            LoginCredential = $LoginCredential
            ServerPort = 8089
            FrontendPort = 5174
            CacheNamespace = 'lamp:rehearsal'
        } | Export-Clixml -LiteralPath $statePath
    }
    $state = Import-Clixml -LiteralPath $statePath
    if ($state.DbUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:3306/mes_lamp_rehearsal\?') { throw 'Unexpected rehearsal target.' }
    $env:MYSQL_PWD = $state.DbCredential.GetNetworkCredential().Password
    & python scripts/apply-lamp-foundation.py
    if ($LASTEXITCODE -ne 0) { throw 'Migration failed.' }
    & python scripts/apply-lamp-foundation.py
    if ($LASTEXITCODE -ne 0) { throw 'Repeated migration failed.' }
    & python scripts/apply-lamp-preparation.py --repeat 2
    if ($LASTEXITCODE -ne 0) { throw 'Manufacturing preparation migration failed.' }
    & python scripts/apply-lamp-report-baseline.py --apply --repeat 2
    if ($LASTEXITCODE -ne 0) { throw 'Report V1.8 alignment failed; review confirmed/published data before retrying.' }
    & python scripts/apply-lamp-execution.py --apply --repeat 2
    if ($LASTEXITCODE -ne 0) { throw 'Execution structure migration failed; business execution remains blocked.' }
    'Lamp foundation ready; existing database was not replaced.'
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    Remove-Item Env:MYSQL_ADMIN_USER -ErrorAction SilentlyContinue
    Pop-Location
}
