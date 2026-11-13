param(
    [string]$RuntimeDirectory = 'D:\AI-MES-Stage2',
    [ValidateRange(1,65535)][int]$PostgresPort = 55432,
    [ValidateRange(1,30)][int]$TimeoutSeconds = 7,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$state = Import-Clixml -LiteralPath (Join-Path $RuntimeDirectory 'mes-runtime.clixml')
foreach ($name in 'DatabasePort','FrontendPort','BackendPort','AssistantPort') {
    $port = 0
    if (-not [int]::TryParse([string]$state.$name, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw "Invalid port in the Stage 2 runtime state: $name"
    }
}

function Test-LocalPort([int]$Port) {
    $socket = [Net.Sockets.TcpClient]::new()
    try {
        return $socket.ConnectAsync('127.0.0.1', $Port).Wait($TimeoutSeconds * 1000) -and $socket.Connected
    } catch {
        return $false
    } finally {
        $socket.Dispose()
    }
}

function Read-LocalHttp([int]$Port, [string]$Path, [switch]$ReadHealth) {
    try {
        $option = if ($ReadHealth) { [Net.Http.HttpCompletionOption]::ResponseContentRead }
                  else { [Net.Http.HttpCompletionOption]::ResponseHeadersRead }
        $response = $client.GetAsync("http://127.0.0.1:${Port}$Path", $option).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            $body = $null
            $detail = "HTTP $status"
            if ($ReadHealth -and $status -eq 200) {
                try {
                    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                    if ($body.ready -cne $true -or $body.ready -isnot [bool]) { $detail += '; health ready flag missing or false' }
                } catch {
                    $detail += '; invalid health JSON'
                }
            }
            return @{ Status = $status; Health = $body; Detail = $detail }
        } finally {
            $response.Dispose()
        }
    } catch {
        $reason = if ($_.Exception.GetBaseException() -is [OperationCanceledException]) { 'Timed out' }
                  else { 'Connection failed' }
        return @{ Status = $null; Health = $null; Detail = $reason }
    }
}

$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
try {
    $mysql = Test-LocalPort $state.DatabasePort
    $postgres = Test-LocalPort $PostgresPort
    $frontend = Read-LocalHttp $state.FrontendPort '/'
    $backend = Read-LocalHttp $state.BackendPort '/api/auth/info'
    $proxy = Read-LocalHttp $state.FrontendPort '/api/auth/info'
    $knowledge = Read-LocalHttp $state.AssistantPort '/health' -ReadHealth
} finally {
    $client.Dispose()
}

$checks = @(
    [pscustomobject]@{ Check='MySQL TCP'; Port=$state.DatabasePort; Passed=$mysql; Detail='TCP listener only' }
    [pscustomobject]@{ Check='PostgreSQL TCP'; Port=$PostgresPort; Passed=$postgres; Detail='TCP listener only' }
    [pscustomobject]@{ Check='Frontend'; Port=$state.FrontendPort; Passed=($frontend.Status -eq 200); Detail=$frontend.Detail }
    [pscustomobject]@{ Check='Backend auth'; Port=$state.BackendPort; Passed=($backend.Status -eq 401); Detail="$($backend.Detail); unauthenticated probe" }
    [pscustomobject]@{ Check='Frontend proxy auth response'; Port=$state.FrontendPort; Passed=($proxy.Status -eq 401); Detail="$($proxy.Detail); target instance not verified" }
    [pscustomobject]@{ Check='Private assistant + database'; Port=$state.AssistantPort; Passed=($knowledge.Status -eq 200 -and $knowledge.Health.ready -is [bool] -and $knowledge.Health.ready); Detail=$knowledge.Detail }
)
$passed = @($checks | Where-Object { -not $_.Passed }).Count -eq 0
$report = [pscustomobject]@{
    Passed = $passed
    Checks = $checks
    ModelManifestPresent = ($knowledge.Health.modelsPresent -is [bool] -and $knowledge.Health.modelsPresent)
    Scope = 'Transport and private database checks. Verify account access, retrieval, graph and business query from the demo account.'
}
if ($Json) {
    $report | ConvertTo-Json -Depth 5
} else {
    $checks | Format-Table -AutoSize
    Write-Output "Model manifest present: $($report.ModelManifestPresent)"
    Write-Output $report.Scope
}
if (-not $passed) { exit 1 }
exit 0
