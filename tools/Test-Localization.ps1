param([string]$OutputPath = "docs/validation/2026-09-30/localization.json")
$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '../android-app/src/main/res'
function Read-Strings([string]$directory) {
    $strings = @{}
    Get-ChildItem -LiteralPath $directory -Filter '*.xml' | ForEach-Object {
        $document = [xml](Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8)
        $document.resources.string | Where-Object { $_.name -and $_.translatable -ne 'false' } | ForEach-Object {
            if ($strings.ContainsKey($_.name)) { throw "Duplicate resource $($_.name)" }
            $strings[$_.name] = $_.InnerText
        }
    }
    return $strings
}
function Placeholders([string]$text) {
    return (@([regex]::Matches($text, '%(?:\d+\$)?[dsf]') | ForEach-Object Value | Sort-Object) -join ',')
}
$base = Read-Strings (Join-Path $root 'values')
$reports = foreach ($language in @('hi', 'sat')) {
    $localized = Read-Strings (Join-Path $root "values-$language")
    $missing = @($base.Keys | Where-Object { -not $localized.ContainsKey($_) } | Sort-Object)
    $mismatched = @($base.Keys | Where-Object {
        $localized.ContainsKey($_) -and (Placeholders $base[$_]) -ne (Placeholders $localized[$_])
    } | Sort-Object)
    [pscustomobject]@{
        language = $language
        baseCount = $base.Count
        localizedCount = $localized.Count
        missing = $missing
        mismatchedPlaceholders = $mismatched
        linguisticReview = 'Not performed; resource presence does not establish translation quality.'
    }
}
$reports | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding utf8
$reports | Select-Object language, baseCount, localizedCount, @{n='missing';e={$_.missing.Count}}, @{n='formatErrors';e={$_.mismatchedPlaceholders.Count}} | Format-Table
if (@($reports | Where-Object { $_.missing.Count -gt 0 -or $_.mismatchedPlaceholders.Count -gt 0 }).Count -gt 0) { exit 1 }
