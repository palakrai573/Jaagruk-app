# Phase 2: moves the app source from com.infinity.ai to org.jaagruk.safety.
#
# Reads and writes with an explicit UTF-8-no-BOM encoder rather than Set-Content, because these
# files contain em dashes and box-drawing characters that the default encoding turns into mojibake.
# The Kotlin compiler does not care, but a source file full of "â" is not something to hand over.
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$oldRoot = "$d\app\src\main\java\com\infinity\ai"
$newRoot = "$d\app\src\main\java\org\jaagruk\safety"
$utf8 = New-Object System.Text.UTF8Encoding($false)

# --- 1. move the tree, preserving package subdirectories -----------------------
if (Test-Path $oldRoot) {
    New-Item -ItemType Directory -Path $newRoot -Force | Out-Null
    robocopy $oldRoot $newRoot /E /MOVE /NFL /NDL /NJH /NJS /NP | Out-Null
    Remove-Item "$d\app\src\main\java\com" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "$d\app\src\test\java\com" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item "$d\app\src\androidTest\java\com" -Recurse -Force -ErrorAction SilentlyContinue
    "moved source tree -> org/jaagruk/safety"
}

# --- 2. rewrite package and import statements, plus class renames --------------
# Ordered: the package rename first, then the type renames, so a renamed type is not
# matched again by the package pattern.
$substitutions = [ordered]@{
    'com\.infinity\.ai'      = 'org.jaagruk.safety'
    'InfinityTheme'          = 'JaagrukTheme'
    'InfinityOverlayService' = 'JaagrukOverlayService'
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

# --- 3. rename the files whose names carry the old brand ----------------------
$fileRenames = @{
    "$newRoot\circle\InfinityOverlayService.kt" = "JaagrukOverlayService.kt"
}
foreach ($from in $fileRenames.Keys) {
    if (Test-Path $from) {
        Rename-Item $from $fileRenames[$from] -Force
        "renamed $(Split-Path $from -Leaf) -> $($fileRenames[$from])"
    }
}

# --- 4. report anything still carrying the old brand -------------------------
"--- remaining 'infinity' references in source (case-insensitive) ---"
Get-ChildItem "$d\app\src" -Recurse -Include *.kt, *.xml -File |
    Select-String -Pattern 'infinity' -CaseSensitive:$false |
    ForEach-Object { "  $($_.Filename):$($_.LineNumber)  $($_.Line.Trim())" }
