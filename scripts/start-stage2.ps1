param(
    [ValidateSet('all','assistant','backend','frontend')][string]$Service='all',
    [string]$RuntimeDirectory='D:\AI-MES-Stage2',
    [switch]$NoCloud
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'load-stage2-mes-environment.ps1') -RuntimeDirectory $RuntimeDirectory -NoCloud:$NoCloud
function Assert-Stage2Listener([string]$HostName,[int]$Port,[string]$Dependency) {
    $socket=[Net.Sockets.TcpClient]::new()
    try {
        $connection=$socket.ConnectAsync($HostName,$Port)
        if(-not $connection.Wait(2000) -or -not $socket.Connected) { throw 'unavailable' }
    } catch {
        throw ($Dependency+' is not listening on '+$HostName+':'+$Port+'. Start the existing database instance and retry. No database was initialized or replaced.')
    } finally { $socket.Dispose() }
}
if($Service -in @('all','backend')) {
    Assert-Stage2Listener '127.0.0.1' $stage2State.DatabasePort 'MES MySQL'
}
if($Service -in @('all','assistant')) {
    $privatePgHost=if($env:PRIVATE_PG_HOST) { $env:PRIVATE_PG_HOST } else { '127.0.0.1' }
    $privatePgPort=if($env:PRIVATE_PG_PORT) { [int]$env:PRIVATE_PG_PORT } else { 55432 }
    Assert-Stage2Listener $privatePgHost $privatePgPort 'Private PostgreSQL'
}
$logs=Join-Path $RuntimeDirectory 'logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$services=if($Service -eq 'all') { @('assistant','backend','frontend') } else { @($Service) }
$powershell=Join-Path $PSHOME 'pwsh.exe'
if(-not (Test-Path -LiteralPath $powershell)) { $powershell=Join-Path $PSHOME 'powershell.exe' }
if(-not (Test-Path -LiteralPath $powershell)) { throw 'Could not locate this PowerShell host.' }
foreach($entry in $services) {
    $port=switch($entry) { 'assistant' {$stage2State.AssistantPort}; 'backend' {$stage2State.BackendPort}; 'frontend' {$stage2State.FrontendPort} }
    if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Output ($entry + ' port is already listening: ' + $port + '. No existing process was changed.')
        continue
    }
    $script=Join-Path $PSScriptRoot 'run-stage2-service.ps1'
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$script+'"'),'-Service',$entry,'-RuntimeDirectory',('"'+$RuntimeDirectory+'"'))
    if($NoCloud) { $arguments+='-NoCloud' }
    $stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
    $toolsKey=$env:PRIVATE_DEEPSEEK_TOOLS_KEY
    if($entry -ne 'assistant' -or $NoCloud) { $env:PRIVATE_DEEPSEEK_TOOLS_KEY='' }
    try {
        $child=Start-Process -FilePath $powershell -ArgumentList $arguments -WorkingDirectory (Split-Path -Parent $PSScriptRoot) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs ($entry+'-'+$stamp+'.log')) -RedirectStandardError (Join-Path $logs ($entry+'-'+$stamp+'.err')) -PassThru
    } finally {
        $env:PRIVATE_DEEPSEEK_TOOLS_KEY=$toolsKey
        $toolsKey=$null
    }
    [pscustomobject]@{Service=$entry;ParentProcessId=$child.Id;Port=$port;StartedUtc=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RuntimeDirectory ($entry+'-process.json')) -Encoding UTF8
    Write-Output ($entry + ' starting on 127.0.0.1:' + $port)
}
