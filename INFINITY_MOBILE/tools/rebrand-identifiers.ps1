# Phase 2, stage 2: the identifiers that are not user-visible strings.
#
# Storage names change freely here because applicationId changes in the same phase, so every
# install is a fresh one: there is no existing database or preference file to orphan.
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$substitutions = [ordered]@{
    # Android theme
    'Theme\.Infinity'          = 'Theme.Jaagruk'

    # Storage identifiers
    'infinity_library\.db'     = 'jaagruk_library.db'
    'infinity_prefs'           = 'jaagruk_prefs'
    'infinity_overlay'         = 'jaagruk_overlay'

    # The floating bubble draws a lemniscate. That shape WAS the Infinity brand mark, and it
    # survives only as a placeholder until phase 3 designs the Jaagruk mark. Renaming to the
    # geometric term now so nobody later reads it as intentional branding.
    'drawInfinityStatic'       = 'drawLemniscateStatic'
    'drawInfinityTrace'        = 'drawLemniscateTrace'
    'infinityPath'             = 'lemniscatePath'
    '// ── Infinity path phase' = '// ── Lemniscate path phase'
    '// ── Infinity symbol'    = '// ── Placeholder mark (lemniscate) — replaced in phase 3'
    '// ── Static infinity glyph' = '// ── Static lemniscate glyph'
    '// ── Animated trace infinity' = '// ── Animated lemniscate trace'
    '// ── Infinity lemniscate path builder' = '// ── Lemniscate path builder'
    '// ── Open Infinity app'  = '// ── Open the Jaagruk app'
}

$changed = 0
Get-ChildItem "$d\app\src" -Recurse -Include *.kt, *.xml -File | ForEach-Object {
    $text = [System.IO.File]::ReadAllText($_.FullName)
    $original = $text
    foreach ($pattern in $substitutions.Keys) {
        $text = $text -replace $pattern, $substitutions[$pattern]
    }
    if ($text -ne $original) {
        [System.IO.File]::WriteAllText($_.FullName, $text, $utf8)
        $changed++
    }
}
"rewrote $changed files"

"--- remaining 'infinity' references, which should now be only user-visible strings and the icon ---"
Get-ChildItem "$d\app\src" -Recurse -Include *.kt, *.xml -File |
    Select-String -Pattern 'infinity' -CaseSensitive:$false |
    ForEach-Object { "  $($_.Filename):$($_.LineNumber)  $($_.Line.Trim())" }
