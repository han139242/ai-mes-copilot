param([string]$RuntimeDirectory='D:\AI-MES-Stage2',[switch]$NoCloud)
$ErrorActionPreference='Stop'
$configurationPath=Join-Path $RuntimeDirectory 'private-env.ps1'
if(-not (Test-Path -LiteralPath $configurationPath)) {
    throw 'Private runtime configuration is missing. Recover existing configuration before initializing any data.'
}
. $configurationPath
$modelConfigurationPath=Join-Path $RuntimeDirectory 'model-secret.clixml'
if($NoCloud) {
    $env:PRIVATE_LLM_URL=''
    $env:PRIVATE_LLM_KEY=''
    $env:PRIVATE_LLM_MODEL=''
    $env:PRIVATE_DEEPSEEK_TOOLS_KEY=''
    $env:PRIVATE_DEEPSEEK_TOOLS_ENABLED='0'
    $env:PRIVATE_DEEPSEEK_SAFETY='0'
} elseif(Test-Path -LiteralPath $modelConfigurationPath) {
    $modelConfiguration=Import-Clixml -LiteralPath $modelConfigurationPath
    if($modelConfiguration.ApiKey -isnot [Security.SecureString]) {
        throw 'Expected a Windows user-encrypted model credential.'
    }
    $env:PRIVATE_LLM_URL=[string]$modelConfiguration.ApiUrl
    $env:PRIVATE_LLM_MODEL=[string]$modelConfiguration.Model
    $env:PRIVATE_LLM_KEY=([pscredential]::new('private-model',$modelConfiguration.ApiKey)).GetNetworkCredential().Password
    $modelConfiguration=$null
} else {
    $env:PRIVATE_LLM_URL=[Environment]::GetEnvironmentVariable('AI_API_URL','User')
    $env:PRIVATE_LLM_KEY=[Environment]::GetEnvironmentVariable('AI_API_KEY','User')
    $env:PRIVATE_LLM_MODEL=[Environment]::GetEnvironmentVariable('AI_MODEL','User')
}
$env:PYTHONUTF8='1'
$env:HF_HUB_DISABLE_TELEMETRY='1'
$env:HF_HOME=Join-Path $RuntimeDirectory 'models\huggingface'
$env:TIKTOKEN_CACHE_DIR=Join-Path $RuntimeDirectory 'models\tokenizer'
$env:PIP_CACHE_DIR=Join-Path $RuntimeDirectory 'pip-cache'
