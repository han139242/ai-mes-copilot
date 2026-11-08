param(
    [string]$SourceEnvironmentScript,
    [Parameter(Mandatory=$true)][string]$SourceDatabase,
    [string]$TargetDatabase=('mes_lamp_stage2_' + (Get-Date -Format 'yyyyMMdd')),
    [int]$DatabasePort=3307,
    [string]$MysqlBin='D:\MySQL\MySQL Server 8.0\bin',
    [string]$RuntimeDirectory='D:\AI-MES-Stage2'
)
$ErrorActionPreference='Stop'
$statePath=Join-Path $RuntimeDirectory 'mes-runtime.clixml'
if(Test-Path -LiteralPath $statePath) { throw 'Stage-2 MES configuration exists; keep it and use the start script.' }
if($SourceEnvironmentScript) { . $SourceEnvironmentScript }
if(-not $env:DB_USERNAME -or -not $env:DB_PASSWORD) { throw 'Explicit source database credentials are required.' }
$python=Join-Path $RuntimeDirectory '.venv\Scripts\python.exe'
$env:PYTHONUTF8='1'
& $python (Join-Path $PSScriptRoot 'prepare-stage2-mes.py') --source $SourceDatabase --target $TargetDatabase --port $DatabasePort --mysql-bin $MysqlBin --runtime $RuntimeDirectory
if($LASTEXITCODE -ne 0) { throw 'Database preparation did not pass. No configuration was replaced.' }
$randomBytes=New-Object byte[] 48
$generator=[Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
$state=[pscustomobject]@{
    Database=$TargetDatabase; DatabasePort=$DatabasePort
    DbCredential=[pscredential]::new($env:DB_USERNAME,(ConvertTo-SecureString $env:DB_PASSWORD -AsPlainText -Force))
    JwtSecret=(ConvertTo-SecureString ([Convert]::ToBase64String($randomBytes)) -AsPlainText -Force)
    BackendPort=8089; FrontendPort=5181; AssistantPort=8091
    Scope=('mes-stage2-' + (Get-Date -Format 'yyyyMMdd'))
}
$state | Export-Clixml -LiteralPath $statePath
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $statePath /inheritance:r /grant:r ($identity + ':(F)') 'SYSTEM:(F)' 'BUILTIN\Administrators:(F)' | Out-Null
if($LASTEXITCODE -ne 0) { throw 'Could not restrict the new private configuration permissions.' }
Write-Output 'Isolated stage-2 database copied and verified; Windows-encrypted runtime configuration saved.'
