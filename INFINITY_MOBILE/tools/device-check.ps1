# Queries the attached handset and writes results to a file.
# Output goes to a file because this session's shell mangles piped adb output.
param([string]$Serial = 'RZCY90QZFRD', [string]$Tag = 'device')
$adb = 'D:\Android_SDK\platform-tools\adb.exe'
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$out = "$d\_$Tag.txt"

"=== devices ===" | Out-File $out -Encoding utf8
& $adb devices | Out-File $out -Append -Encoding utf8

"=== model / android / abi / ram ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abi; grep MemTotal /proc/meminfo" | Out-File $out -Append -Encoding utf8

"=== /data free ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell "df -h /data" | Out-File $out -Append -Encoding utf8

"=== installed packages of interest ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell "pm list packages | grep -E 'infinity|jaagruk'" | Out-File $out -Append -Encoding utf8

"=== keep awake ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell "svc power stayon usb" | Out-File $out -Append -Encoding utf8
