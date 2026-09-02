<#
.SYNOPSIS
    Starts the Jaagruk backend for local development or a demo.

.DESCRIPTION
    Creates the virtual environment if it is missing, installs requirements, seeds a realistic database on
    request, and starts uvicorn.

    Seeding is opt-in rather than automatic. `--reset` drops and rebuilds every table, and a script that did
    that on every run would eventually destroy a database somebody was demoing from.

.PARAMETER Seed
    Reseeds the database before starting. Destructive: drops and recreates all tables.

.PARAMETER Port
    Port to bind. Defaults to 8000, which is what the Android debug build and the dashboard dev server expect.

.PARAMETER BindHost
    Address to bind. Defaults to 127.0.0.1. Use 0.0.0.0 to let a phone on the same Wi-Fi reach it, and pass
    that machine's LAN address to the app with -Pjaagruk.apiBaseUrl.

.EXAMPLE
    .\tools\run-backend.ps1 -Seed

.EXAMPLE
    # Reachable from a handset on the same network.
    .\tools\run-backend.ps1 -BindHost 0.0.0.0
#>
[CmdletBinding()]
param(
    [switch]$Seed,
    [int]$Port = 8000,
    [string]$BindHost = '127.0.0.1'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repoRoot 'backend'
$venv = Join-Path $backend '.venv'
$python = Join-Path $venv 'Scripts\python.exe'

if (-not (Test-Path -LiteralPath $backend)) {
    throw "backend/ not found under $repoRoot"
}

if (-not (Test-Path -LiteralPath $python)) {
    Write-Host 'Creating the virtual environment...' -ForegroundColor Cyan
    & py -3.11 -m venv $venv
    if ($LASTEXITCODE -ne 0) {
        # Falls back to whatever `python` resolves to. 3.11 is what this was developed against, but 3.12
        # works; anything below 3.10 will fail on the match statements and the `X | None` annotations.
        Write-Host 'py -3.11 unavailable; falling back to python on PATH' -ForegroundColor Yellow
        & python -m venv $venv
    }
}

Write-Host 'Installing requirements...' -ForegroundColor Cyan
& $python -m pip install --upgrade pip --quiet
& $python -m pip install -r (Join-Path $backend 'requirements.txt') --quiet

if ($Seed) {
    Write-Host 'Seeding the database (this drops and recreates every table)...' -ForegroundColor Yellow
    Push-Location $backend
    try {
        & $python -m app.seed --reset
        if ($LASTEXITCODE -ne 0) { throw 'Seeding failed.' }
    } finally {
        Pop-Location
    }
    Write-Host 'Seeded. Demo password for every account: JaagrukDemo2026!' -ForegroundColor Green
}

Write-Host "Starting uvicorn on http://${BindHost}:${Port} ..." -ForegroundColor Cyan
Write-Host "  API docs:  http://${BindHost}:${Port}/docs" -ForegroundColor DarkGray
Write-Host "  Health:    http://${BindHost}:${Port}/api/v1/health" -ForegroundColor DarkGray

Push-Location $backend
try {
    # --reload is deliberately absent. It doubles memory and, more importantly, restarts the process on any
    # file touch, which drops the WebSocket connections the dashboard's live view depends on - exactly the
    # thing somebody demoing would notice.
    & $python -m uvicorn app.main:app --host $BindHost --port $Port
} finally {
    Pop-Location
}
