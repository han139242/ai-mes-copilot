param([string]$RuntimeDirectory='D:\AI-MES-Stage2',[switch]$NoCloud)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'load-stage2-environment.ps1') -RuntimeDirectory $RuntimeDirectory -NoCloud:$NoCloud
$statePath=Join-Path $RuntimeDirectory 'mes-runtime.clixml'
if(-not (Test-Path -LiteralPath $statePath)) { throw 'Initialize the isolated stage-2 MES database before starting.' }
$stage2State=Import-Clixml -LiteralPath $statePath
if($stage2State.Database -notmatch '^mes_lamp_stage2_[0-9]{8}(_[a-z0-9]+)?$') { throw 'Expected an isolated stage-2 database.' }
$env:DB_URL='jdbc:mysql://127.0.0.1:' + $stage2State.DatabasePort + '/' + $stage2State.Database + '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:DB_USERNAME=$stage2State.DbCredential.UserName
$env:DB_PASSWORD=$stage2State.DbCredential.GetNetworkCredential().Password
$env:JWT_SECRET=([pscredential]::new('stage2-jwt',$stage2State.JwtSecret)).GetNetworkCredential().Password
$env:SERVER_PORT=[string]$stage2State.BackendPort
$env:VITE_API_PROXY_TARGET='http://127.0.0.1:' + $stage2State.BackendPort
$env:VITE_APP_SCOPE=$stage2State.Scope
$env:CACHE_NAMESPACE=$stage2State.Scope
$env:PRIVATE_ASSISTANT_ENABLED='true'
$env:PRIVATE_ASSISTANT_URL='http://127.0.0.1:' + $stage2State.AssistantPort
$env:FILE_UPLOAD_ROOT=Join-Path $RuntimeDirectory 'mes-files\uploads'
$env:FILE_UPLOAD_TEMP_ROOT=Join-Path $RuntimeDirectory 'mes-files\temp'
$env:NOTIFICATION_SCHEDULING_ENABLED='false'
$env:DB_POOL_MAX_SIZE='8'
$env:DB_POOL_MIN_IDLE='2'
# Existing encrypted phone fields use the selected local machine's original keys.
foreach($item in [Environment]::GetEnvironmentVariables('User').GetEnumerator()) {
    if([string]$item.Key -match '^PHONE_(CRYPTO_|LOOKUP_)') { Set-Item -LiteralPath ('Env:'+[string]$item.Key) -Value ([string]$item.Value) }
}
if($NoCloud -or -not $env:PRIVATE_LLM_KEY) {
    $env:AI_ENABLED='false'; $env:AI_API_KEY=''
} else {
    # Both old MES planning and new RAG calls must respect the same persisted validation limit.
    $hmac=[Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($env:PRIVATE_ASSISTANT_SECRET))
    try { $token=$hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes('mes-business-model-v1')) } finally { $hmac.Dispose() }
    $env:AI_API_KEY=([BitConverter]::ToString($token)).Replace('-','').ToLowerInvariant()
    $env:AI_API_URL=$env:PRIVATE_ASSISTANT_URL + '/internal/model/v1/chat/completions'
    $env:AI_MODEL=$env:PRIVATE_LLM_MODEL
    $env:AI_ENABLED='true'
}
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
if(-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'JDK 21 is required. Select its installed location.' }
$env:Path=(Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
