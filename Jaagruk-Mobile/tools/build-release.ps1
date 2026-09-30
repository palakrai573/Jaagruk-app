param([switch]$CreateSigningKey, [string]$BuildDirectory)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$signing = Join-Path $root '.signing'
$key = Join-Path $signing 'release.p12'
$secret = Join-Path $signing 'password.xml'
if ($CreateSigningKey) {
    if ((Test-Path -LiteralPath $key) -or (Test-Path -LiteralPath $secret)) {
        throw 'Signing material already exists. Refusing to replace it.'
    }
    New-Item -ItemType Directory -Path $signing -Force | Out-Null
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $password = [Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $password -AsPlainText -Force | Export-Clixml -LiteralPath $secret
    $env:JAAGRUK_SIGNING_PASSWORD = $password
    & keytool -genkeypair -keystore $key -storetype PKCS12 -alias jaagruk-release `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Jaagruk Local Release' `
        -storepass:env JAAGRUK_SIGNING_PASSWORD -keypass:env JAAGRUK_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
}
if (!(Test-Path -LiteralPath $key) -or !(Test-Path -LiteralPath $secret)) {
    throw 'No release key. Run once with -CreateSigningKey to create a local signing identity.'
}
try {
    $secure = Import-Clixml -LiteralPath $secret
    $env:JAAGRUK_SIGNING_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
    Push-Location $root
    try {
        $arguments = @(':app:assembleBundledRelease', '--max-workers=2')
        if ($BuildDirectory) {
            # Avoid duplicating the model in a cache on the original (possibly full) drive.
            # KSP requires generated sources to have the same Windows drive root as source.
            # A junction preserves that logical path while putting bytes on the selected drive.
            New-Item -ItemType Directory -Path $BuildDirectory -Force | Out-Null
            $destination = (Resolve-Path -LiteralPath $BuildDirectory).Path
            if ([System.IO.Path]::GetPathRoot($destination) -ne [System.IO.Path]::GetPathRoot($root)) {
                $link = Join-Path $root 'app/.release-build'
                if (Test-Path -LiteralPath $link) {
                    $item = Get-Item -LiteralPath $link
                    if ($item.LinkType -ne 'Junction' -or $item.Target -ne $destination) {
                        throw 'Release build junction exists with a different target; refusing to replace it.'
                    }
                } else {
                    New-Item -ItemType Junction -Path $link -Target $destination | Out-Null
                }
                $BuildDirectory = $link
            }
            $arguments += @("-Pjaagruk.appBuildDir=$BuildDirectory", '--no-build-cache',
                '-Pksp.incremental=false', '-Pkotlin.incremental=false')
        }
        & .\gradlew.bat @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:JAAGRUK_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
