# Reports how much of the app still uses the compatibility aliases in Color.kt.
#
# On demand rather than as build warnings: marking the aliases @Deprecated produced about four
# hundred warnings per compile, which hides the warnings that matter. This is the same
# information as a migration checklist you can ask for.
#
# Also reports hardcoded hex colours, which are the other way a screen escapes the palette.
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\Jaagruk-Mobile'
$k = "$d\app\src\main\java\org\jaagruk\safety"
$files = Get-ChildItem $k -Recurse -Filter *.kt | Where-Object { $_.FullName -notmatch '\\ui\\theme\\' }

$aliases = @(
    'DarkBg', 'DarkSurface', 'DarkSurfaceElevated', 'DarkBorder', 'DarkGlass',
    'LightBg', 'LightSurface', 'LightSurfaceElevated', 'LightBorder', 'LightGlass',
    'Blue500', 'Blue600', 'Blue400', 'Blue50', 'Blue100', 'BlueAlpha12',
    'GradStart', 'GradMid', 'GradEnd', 'OrbColor1', 'OrbColor2', 'OrbColor3',
    'TextPrimary', 'TextSecondary', 'TextDisabled', 'TextPrimaryLight',
    'TextSecondaryLight', 'TextTertiary', 'SuccessGreen', 'ErrorRed', 'WarnAmber'
)

"=== compatibility aliases still in use ==="
$total = 0
foreach ($alias in $aliases) {
    $count = ($files | Select-String -Pattern "\b$alias\b" -AllMatches |
        ForEach-Object { $_.Matches.Count } | Measure-Object -Sum).Sum
    if ($count) { "{0,5}  {1}" -f $count, $alias; $total += $count }
}
"{0,5}  TOTAL" -f $total

"`n=== files with the most alias references (rebuild these first) ==="
$pattern = '\b(' + ($aliases -join '|') + ')\b'
$files | Select-String -Pattern $pattern -AllMatches |
    Group-Object Filename |
    Sort-Object { ($_.Group | ForEach-Object { $_.Matches.Count } | Measure-Object -Sum).Sum } -Descending |
    Select-Object -First 10 |
    ForEach-Object {
        "{0,5}  {1}" -f ($_.Group | ForEach-Object { $_.Matches.Count } | Measure-Object -Sum).Sum, $_.Name
    }

"`n=== hardcoded hex colours outside ui/theme (should be zero) ==="
$hex = $files | Select-String -Pattern '0x[0-9A-Fa-f]{8}' -AllMatches
if ($hex) {
    $hex | ForEach-Object { $_.Matches.Value } | Group-Object | Sort-Object Count -Descending |
        ForEach-Object { "{0,5}  {1}" -f $_.Count, $_.Name }
} else {
    "  none"
}
