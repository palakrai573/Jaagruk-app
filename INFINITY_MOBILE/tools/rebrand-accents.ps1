# Phase 3, final pass: the per-feature accent colours the screens hardcoded.
#
# The palette rewrite re-skinned everything that went through a theme symbol - 496 references -
# but a screen that wrote Color(0xFF8B5CF6) inline escaped it. The visible result was violet and
# emerald feature cards sitting inside a teal app, which is worse than either colour alone.
#
# Each old accent maps onto the nearest palette member, in the chart-series order, so features stay
# distinguishable from each other while belonging to one scheme.
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$k = "$d\app\src\main\java\org\jaagruk\safety"
$utf8 = New-Object System.Text.UTF8Encoding($false)

$map = [ordered]@{
    'Color(0xFF8B5CF6)' = 'Indigo500'  # violet
    'Color(0xFF7C3AED)' = 'Indigo500'  # deeper violet
    'Color(0xFF6366F1)' = 'Indigo500'  # indigo
    'Color(0xFF10B981)' = 'Moss500'    # emerald
    'Color(0xFFF59E0B)' = 'Amber400'   # amber
    'Color(0xFFEF4444)' = 'Clay500'    # red used as an accent, not a signal
    'Color(0xFFDC2626)' = 'SignalRed'  # red used as an error
    'Color(0xFF3B82F6)' = 'Teal500'    # old brand blue
    'Color(0xFF06B6D4)' = 'Teal500'    # cyan
    'Color(0xFF4F8CFF)' = 'Teal700'    # old primary
    'Color(0xFFEAECF0)' = 'Sand200'    # cold grey border
    'Color(0xFFD8DEE4)' = 'Sand200'    # cold grey border
}

$files = Get-ChildItem $k -Recurse -Filter *.kt |
    Where-Object { $_.FullName -notmatch '\\ui\\(theme|charts)\\' }

$changedFiles = 0
$totalEdits = 0
foreach ($file in $files) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $original = $text
    foreach ($from in $map.Keys) {
        if ($text.Contains($from)) {
            $count = ([regex]::Matches($text, [regex]::Escape($from))).Count
            $text = $text.Replace($from, $map[$from])
            $totalEdits += $count
        }
    }
    if ($text -eq $original) { continue }

    # The palette lives in ui.theme; a file that only ever used literals will not have imported it.
    if ($text -notmatch 'import org\.jaagruk\.safety\.ui\.theme\.') {
        $lines = $text -split "`r?`n"
        $lastImport = ($lines | Select-String -Pattern '^import ' | Select-Object -Last 1).LineNumber
        if ($lastImport) {
            $lines = @($lines[0..($lastImport - 1)]) +
                     @('import org.jaagruk.safety.ui.theme.*') +
                     @($lines[$lastImport..($lines.Count - 1)])
            $text = $lines -join "`r`n"
        }
    }
    [System.IO.File]::WriteAllText($file.FullName, $text, $utf8)
    $changedFiles++
}
"rewrote $totalEdits accent literals across $changedFiles files"

"`n=== any opaque hex left outside ui/theme and ui/charts? ==="
$left = $files | Select-String -Pattern 'Color\(0xFF[0-9A-Fa-f]{6}\)'
if ($left) {
    $left | ForEach-Object { "  $($_.Filename):$($_.LineNumber)  $($_.Line.Trim())" }
} else {
    "  none"
}
