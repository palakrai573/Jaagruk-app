<#
.SYNOPSIS
    Starts the Jaagruk compliance dashboard dev server.

.DESCRIPTION
    Installs node modules if they are missing, then runs Vite. Expects the backend to be running; the
    dashboard proxies API calls to it and will show a clear connection error rather than blank panels if it
    is not.

.PARAMETER Build
    Produces a production build into dashboard/dist instead of starting the dev server.

.PARAMETER Port
    Dev server port. Defaults to 5173.

.EXAMPLE
    .\tools\run-dashboard.ps1

.EXAMPLE
    .\tools\run-dashboard.ps1 -Build
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [int]$Port = 5173
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$dashboard = Join-Path $repoRoot 'dashboard'

if (-not (Test-Path -LiteralPath $dashboard)) {
    throw "dashboard/ not found under $repoRoot"
}

Push-Location $dashboard
try {
    if (-not (Test-Path -LiteralPath (Join-Path $dashboard 'node_modules'))) {
        Write-Host 'Installing node modules...' -ForegroundColor Cyan
        # `npm ci` when a lockfile exists: it installs exactly what was tested rather than resolving fresh
        # versions, which is the difference between a reproducible demo and a surprise.
        if (Test-Path -LiteralPath (Join-Path $dashboard 'package-lock.json')) {
            & npm ci
        } else {
            & npm install
        }
        if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
    }

    if ($Build) {
        Write-Host 'Type-checking...' -ForegroundColor Cyan
        & npx tsc --noEmit
        if ($LASTEXITCODE -ne 0) { throw 'TypeScript reported errors.' }

        Write-Host 'Building...' -ForegroundColor Cyan
        & npx vite build
        if ($LASTEXITCODE -ne 0) { throw 'Vite build failed.' }

        Write-Host "Built into $(Join-Path $dashboard 'dist')" -ForegroundColor Green
        return
    }

    Write-Host "Starting the dev server on http://localhost:$Port ..." -ForegroundColor Cyan
    Write-Host '  Sign in as inspector.dgms / JaagrukDemo2026!' -ForegroundColor DarkGray
    Write-Host '  The backend must be running: .\tools\run-backend.ps1' -ForegroundColor DarkGray

    & npx vite --port $Port
} finally {
    Pop-Location
}
