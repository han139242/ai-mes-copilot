param(
    [Parameter(Mandatory=$true)][ValidateSet('assistant','backend','frontend')][string]$Service,
    [string]$RuntimeDirectory='D:\AI-MES-Stage2',
    [switch]$NoCloud
)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'load-stage2-mes-environment.ps1') -RuntimeDirectory $RuntimeDirectory -NoCloud:$NoCloud
switch($Service) {
    'assistant' {
        Set-Location -LiteralPath (Join-Path $repository 'services\private-assistant')
        $env:PYTHONPATH=(Get-Location).Path
        & (Join-Path $RuntimeDirectory '.venv\Scripts\python.exe') -m uvicorn assistant.app:app --host 127.0.0.1 --port $stage2State.AssistantPort --no-access-log
    }
    'backend' {
        $backend=Join-Path $repository 'workspace\idea\ems'
        $jar=Join-Path $RuntimeDirectory 'build\backend\ems-0.0.1-SNAPSHOT.jar'
        if(-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend first using the isolated stage-2 environment.' }
        $releaseDirectory=Join-Path $RuntimeDirectory 'mes-release'
        New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
        $digest=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
        $release=Join-Path $releaseDirectory ('mes-'+$digest.Substring(0,16)+'.jar')
        if(-not (Test-Path -LiteralPath $release)) { Copy-Item -LiteralPath $jar -Destination $release }
        if((Get-FileHash -LiteralPath $release -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) { throw 'Staged backend artifact checksum mismatch.' }
        Set-Location -LiteralPath $backend
        & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Xms128m' '-Xmx768m' -jar $release --server.address=127.0.0.1
    }
    'frontend' {
        Set-Location -LiteralPath (Join-Path $repository 'workspace\vscode\ems-forword')
        & npm.cmd run dev -- --host 127.0.0.1 --port $stage2State.FrontendPort --strictPort
    }
}
exit $LASTEXITCODE
