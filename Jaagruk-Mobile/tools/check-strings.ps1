# Asserts that every locale declares exactly the same string keys.
#
# MissingTranslation lint catches this at build time, but only for keys present in the default
# locale. It does NOT catch the reverse - a key in values-hi that no longer exists in values/ -
# which is how a translation file quietly accumulates dead entries. This checks both directions,
# and runs in under a second.
param([string]$ResourceRoot = (Join-Path $PSScriptRoot '../app/src/main/res'))
$ErrorActionPreference = 'Stop'
$locales = @{
    'en'  = Join-Path $ResourceRoot 'values'
    'hi'  = Join-Path $ResourceRoot 'values-hi'
    'sat' = Join-Path $ResourceRoot 'values-sat'
    'ta' = Join-Path $ResourceRoot 'values-ta'
}

$keys = @{}
$nodes = @{}
$failed = $false
foreach ($locale in $locales.Keys) {
    $nodes[$locale] = @{}
    foreach ($file in Get-ChildItem $locales[$locale] -Filter '*.xml') {
        $xml = [xml](Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8)
        foreach ($node in $xml.SelectNodes('/resources/string')) {
            $name = $node.GetAttribute('name')
            if ($nodes[$locale].ContainsKey($name)) { "FAIL duplicate $locale/$name"; $failed = $true }
            $nodes[$locale][$name] = $node
        }
    }
    $keys[$locale] = @($nodes[$locale].Keys | Sort-Object)
    "{0,-4} {1,4} keys" -f $locale, $keys[$locale].Count
}

$reference = $keys['en']
foreach ($locale in @('hi', 'sat', 'ta')) {
    $missing = $reference | Where-Object { $_ -notin $keys[$locale] -and $nodes.en[$_].GetAttribute('translatable') -ne 'false' }
    $extra = $keys[$locale] | Where-Object { $_ -notin $reference }
    if ($missing) { "FAIL $locale is missing: $($missing -join ', ')"; $failed = $true }
    if ($extra) { "FAIL $locale has keys not in en: $($extra -join ', ')"; $failed = $true }
}

# A format specifier that appears in one locale and not another is a crash at runtime, not a
# typo: String.format throws when the argument it was promised is absent.
function Get-FormatSignature([string]$Text) {
    $pattern = '%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])'
    $implicit = 0
    $previous = 0
    $arguments = foreach ($match in [regex]::Matches($Text, $pattern)) {
        $type = $match.Groups[2].Value
        if ($type -in @('%', 'n')) { continue }
        if ($match.Value.Contains('<')) { $index = $previous }
        elseif ($match.Groups[1].Success) { $index = [int]$match.Groups[1].Value }
        else { $implicit++; $index = $implicit }
        $previous = $index
        '{0}:{1}' -f $index, $type
    }
    ($arguments | Sort-Object -Unique) -join ','
}
foreach ($locale in $locales.Keys) {
    foreach ($s in $nodes[$locale].Values) {
        $refNode = $nodes.en[$s.name]
        if (-not $refNode) { continue }
        $expected = Get-FormatSignature $refNode.InnerText
        $actual = Get-FormatSignature $s.InnerText
        if ($expected -cne $actual) {
            "FAIL $locale/$($s.name): expected format [$expected], found [$actual]"
            $failed = $true
        }
    }
}

if ($failed) { "STRING PARITY FAILED"; exit 1 } else { "string parity OK across en / hi / sat / ta"; exit 0 }
