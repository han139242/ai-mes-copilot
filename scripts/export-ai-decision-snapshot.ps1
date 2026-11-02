param(
    [Parameter(Mandatory=$true)][long]$WorkOrderId,
    [Parameter(Mandatory=$true)][long]$InspectionId,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [string]$ConfigPath
)
$ErrorActionPreference = 'Stop'
if ($WorkOrderId -le 0 -or $InspectionId -le 0) { throw 'Document IDs must be positive integers' }
$repo = Split-Path -Parent $PSScriptRoot
if (-not $ConfigPath) { $ConfigPath = Join-Path $repo 'tmp/local-runtime.json' }
$config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($config.database.name -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid database name' }
if ($config.environmentScript) { . $config.environmentScript }
$sql = @"
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT JSON_OBJECT(
 'schemaVersion',1,'database',DATABASE(),'capturedAt',DATE_FORMAT(UTC_TIMESTAMP(3),'%Y-%m-%dT%H:%i:%s.%fZ'),
 'workOrder',(SELECT JSON_OBJECT('id',work_order_id,'no',work_order_no,'status',status,'version',version) FROM mfg_work_order WHERE work_order_id=$WorkOrderId AND deleted_flag=0),
 'components',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',work_order_id,'no',work_order_no,'status',status,'version',version)) FROM mfg_work_order WHERE parent_work_order_id=$WorkOrderId AND deleted_flag=0),JSON_ARRAY()),
 'requirements',COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('materialId',r.material_id,'materialName',m.material_name,'unit',u.unit_name,'quantityMode',m.quantity_mode,'required',CAST(r.required_quantity AS CHAR),'issued',CAST(r.issued_quantity AS CHAR),'supplyType',r.supply_type,'sourceWorkOrderId',r.source_work_order_id)) FROM mfg_work_order_material r LEFT JOIN mdm_material m ON m.material_id=r.material_id AND m.deleted_flag=0 LEFT JOIN mdm_unit u ON u.unit_id=m.unit_id AND u.deleted_flag=0 WHERE r.work_order_id=$WorkOrderId AND r.deleted_flag=0),JSON_ARRAY()),
 'inspection',(SELECT JSON_OBJECT('id',q.inspection_id,'no',q.inspection_no,'workOrderId',q.work_order_id,'status',q.status,'version',q.version,'resultCount',(SELECT COUNT(*) FROM qms_inspection_result qr WHERE qr.inspection_id=q.inspection_id AND qr.deleted_flag=0)) FROM qms_inspection_order q WHERE q.inspection_id=$InspectionId AND q.deleted_flag=0)
);
COMMIT;
"@
$oldPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $env:DB_PASSWORD
    $lines = & $config.database.client --default-character-set=utf8mb4 --host=127.0.0.1 "--port=$($config.database.port)" "--user=$env:DB_USERNAME" "--database=$($config.database.name)" --batch --raw --skip-column-names "--execute=$sql"
    if ($LASTEXITCODE -ne 0) { throw 'Read-only snapshot failed' }
    $json = $lines -join "`n"
    $document = $json | ConvertFrom-Json
    if (-not $document.workOrder -or -not $document.inspection) { throw 'Requested work order or inspection was not found' }
    $fullPath = [IO.Path]::GetFullPath($OutputPath)
    $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($fullPath))
    if (Test-Path -LiteralPath $fullPath) { throw 'Output already exists; select a new file name' }
    [IO.File]::WriteAllText($fullPath, $json, (New-Object Text.UTF8Encoding($false)))
    Write-Output "Read-only snapshot saved: $fullPath"
} finally {
    $env:MYSQL_PWD = $oldPassword
}
