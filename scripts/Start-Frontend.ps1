param([int]$ApiPort=8091)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$env:TRACEFLOW_API_TARGET="http://127.0.0.1:$ApiPort"
Push-Location (Join-Path $root 'frontend')
try {
    if(-not (Test-Path -LiteralPath 'node_modules')) { npm ci --no-audit --no-fund; if($LASTEXITCODE -ne 0){throw 'npm ci failed'} }
    npm run dev
    if($LASTEXITCODE -ne 0){throw 'Frontend exited with an error'}
} finally {Pop-Location}
