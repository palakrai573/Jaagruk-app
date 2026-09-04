<#
.SYNOPSIS
    Runs every check in the repository and prints one verdict.

.DESCRIPTION
    Six stages, in increasing cost order so a cheap failure is reported before an expensive one runs:

      1. :core unit tests             - the load-bearing logic, on a plain JVM, no emulator
      2. Cross-language fixture parity - the same canonical bytes asserted from Kotlin and Python
      3. Backend tests                - FastAPI, SQLAlchemy, RBAC, sync, chain verification
      4. Dashboard type-check + build  - TypeScript and Vite
      5. Android assemble + lint       - APK, plus MissingTranslation and ContentDescription as fatal
      6. Live smoke test               - starts a real server and asserts 56 checks over HTTP

    Stage 5 is skipped automatically when no Android SDK is present. Everything else runs anywhere, which is
    the point of keeping the logic in a plain Kotlin module.

.PARAMETER SkipAndroid
    Skip the Android build even if an SDK is available. Useful on a slow machine.

.PARAMETER SkipSmoke
    Skip the live smoke test, which starts and stops a real server on port 8010.

.EXAMPLE
    .\tools\verify-all.ps1
#>
[CmdletBinding()]
param(
    [switch]$SkipAndroid,
    [switch]$SkipSmoke
)

$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot 'build/verify-logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$results = [System.Collections.Generic.List[object]]::new()

function Invoke-Stage {
    param(
        [string]$Name,
        [string]$LogFile,
        [scriptblock]$Body,
        [switch]$Skipped,
        [string]$SkipReason
    )

    if ($Skipped) {
        Write-Host "SKIP  $Name - $SkipReason" -ForegroundColor DarkGray
        $results.Add([pscustomobject]@{ Stage = $Name; Result = 'SKIP'; Detail = $SkipReason; Log = '' })
        return
    }

    Write-Host "RUN   $Name ..." -ForegroundColor Cyan
    $log = Join-Path $logDir $LogFile
    $start = Get-Date

    & $Body 2>&1 | Out-File -Encoding utf8 -LiteralPath $log
    $ok = $LASTEXITCODE -eq 0
    $seconds = [math]::Round(((Get-Date) - $start).TotalSeconds, 1)

    if ($ok) {
        Write-Host "PASS  $Name (${seconds}s)" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $Name (${seconds}s) - see $log" -ForegroundColor Red
    }
    $results.Add([pscustomobject]@{
        Stage  = $Name
        Result = if ($ok) { 'PASS' } else { 'FAIL' }
        Detail = "${seconds}s"
        Log    = $log
    })
}

Push-Location $repoRoot
try {
    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    $python = Join-Path $repoRoot 'backend/.venv/Scripts/python.exe'

    # --- 1. core -----------------------------------------------------------
    Invoke-Stage -Name ':core unit tests' -LogFile 'core-test.log' -Body {
        & $gradlew --console=plain :core:test
    }

    # --- 2. cross-language fixtures ---------------------------------------
    # Asserted from both sides on purpose. A canonical encoding that only one implementation agrees with is
    # not canonical, and a certificate signed by the phone has to verify byte-for-byte on the server.
    Invoke-Stage -Name 'cross-language fixture parity' -LogFile 'parity.log' `
        -Skipped:(-not (Test-Path -LiteralPath $python)) `
        -SkipReason 'backend venv missing; run tools\run-backend.ps1 once' -Body {
        Push-Location (Join-Path $repoRoot 'backend')
        try { & $python -m pytest tests/test_canonical_parity.py -q } finally { Pop-Location }
    }

    # --- 3. backend --------------------------------------------------------
    Invoke-Stage -Name 'backend tests' -LogFile 'backend-test.log' `
        -Skipped:(-not (Test-Path -LiteralPath $python)) `
        -SkipReason 'backend venv missing; run tools\run-backend.ps1 once' -Body {
        Push-Location (Join-Path $repoRoot 'backend')
        try { & $python -m pytest -q } finally { Pop-Location }
    }

    # --- 4. dashboard ------------------------------------------------------
    $dashboardReady = Test-Path -LiteralPath (Join-Path $repoRoot 'dashboard/node_modules')
    Invoke-Stage -Name 'dashboard type-check and build' -LogFile 'dashboard.log' `
        -Skipped:(-not $dashboardReady) `
        -SkipReason 'node_modules missing; run tools\run-dashboard.ps1 once' -Body {
        Push-Location (Join-Path $repoRoot 'dashboard')
        try {
            & npx tsc --noEmit
            if ($LASTEXITCODE -ne 0) { exit 1 }
            & npx vite build
        } finally { Pop-Location }
    }

    # --- 5. android --------------------------------------------------------
    # Mirrors the detection in settings.gradle.kts rather than assuming: the whole point of keeping :core as a
    # plain JVM module is that the rest of the repo verifies without an SDK.
    $sdkDir = $env:ANDROID_HOME
    if (-not $sdkDir) { $sdkDir = $env:ANDROID_SDK_ROOT }
    if (-not $sdkDir) {
        $localProps = Join-Path $repoRoot 'local.properties'
        if (Test-Path -LiteralPath $localProps) {
            $line = Get-Content -LiteralPath $localProps |
                Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
            if ($line) {
                # `local.properties` is a Java properties file, so backslashes and colons in the value are
                # escaped. Unescaping here rather than pattern-matching once means a path written by Android
                # Studio, by the bootstrap script, or by hand all resolve the same way.
                $raw = ($line -split '=', 2)[1].Trim()
                $sdkDir = $raw -replace '\\:', ':'
                while ($sdkDir -match '\\\\') { $sdkDir = $sdkDir -replace '\\\\', '\' }
            }
        }
    }
    $androidAvailable = $sdkDir -and (Test-Path -LiteralPath $sdkDir)

    Invoke-Stage -Name 'android assemble and lint' -LogFile 'android.log' `
        -Skipped:($SkipAndroid -or -not $androidAvailable) `
        -SkipReason $(if ($SkipAndroid) { 'requested with -SkipAndroid' } else { 'no Android SDK found' }) -Body {
        & $gradlew --console=plain :android-app:assembleDebug :android-app:lintDebug
    }

    # --- 6. live smoke -----------------------------------------------------
    # Starts a real server, exercises it over HTTP, and stops it again. Port 8010 rather than 8000 so this can
    # never collide with a server somebody is demoing from, and a throwaway SQLite file rather than the
    # development database so it cannot destroy seeded demo data.
    Invoke-Stage -Name 'live smoke test' -LogFile 'smoke.log' `
        -Skipped:($SkipSmoke -or -not (Test-Path -LiteralPath $python)) `
        -SkipReason $(if ($SkipSmoke) { 'requested with -SkipSmoke' } else { 'backend venv missing' }) -Body {

        $smokePort = 8010
        $smokeDb = Join-Path $logDir 'smoke.sqlite3'
        Remove-Item -LiteralPath $smokeDb -ErrorAction SilentlyContinue

        $backendDir = Join-Path $repoRoot 'backend'
        $env:JAAGRUK_DATABASE_URL = "sqlite+pysqlite:///$($smokeDb -replace '\\', '/')"

        Push-Location $backendDir
        $server = $null
        try {
            & $python -m app.seed --reset
            if ($LASTEXITCODE -ne 0) { exit 1 }

            $server = Start-Process -FilePath $python `
                -ArgumentList '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$smokePort" `
                -WorkingDirectory $backendDir -PassThru -WindowStyle Hidden

            # Polls rather than sleeping a fixed interval: a cold start on a slow disk takes several seconds,
            # and a fixed sleep is either too short on that machine or wasted time on every other one.
            $ready = $false
            foreach ($attempt in 1..40) {
                Start-Sleep -Milliseconds 500
                try {
                    # /health sits at the root, not under /api/v1. Probes belong outside the versioned API:
                    # a load balancer should not have to know the API version to know the process is alive.
                    $probe = Invoke-WebRequest -Uri "http://127.0.0.1:$smokePort/health" `
                        -UseBasicParsing -TimeoutSec 2
                    if ($probe.StatusCode -eq 200) { $ready = $true; break }
                } catch { }
            }
            if (-not $ready) {
                Write-Output "server did not become healthy on port $smokePort"
                exit 1
            }

            & $python (Join-Path $repoRoot 'tools/smoke_test.py') --base "http://127.0.0.1:$smokePort"
        } finally {
            if ($server -and -not $server.HasExited) {
                Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
            }
            Remove-Item Env:\JAAGRUK_DATABASE_URL -ErrorAction SilentlyContinue
            Pop-Location
        }
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '================ Jaagruk verification ================' -ForegroundColor White
$results | Format-Table -AutoSize Stage, Result, Detail

$failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Result -eq 'SKIP' })

if ($failed.Count -gt 0) {
    Write-Host "$($failed.Count) stage(s) FAILED. Logs are in $logDir" -ForegroundColor Red
    exit 1
}

if ($skipped.Count -gt 0) {
    # Reported rather than glossed over: "everything passed" is a different claim from "everything that could
    # run passed", and only one of them is true here.
    Write-Host "All stages that could run passed. $($skipped.Count) skipped." -ForegroundColor Yellow
    exit 0
}

Write-Host 'Everything passed.' -ForegroundColor Green
exit 0
