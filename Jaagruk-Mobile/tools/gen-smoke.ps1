# Measures on-device generation throughput by reading logcat.
#
# Deliberately takes NO screenshots. An earlier version did, and on a personal handset it captured
# whatever app happened to be in the foreground rather than ours. logcat is both more reliable and
# does not photograph someone's phone.
#
# Prerequisite: open the app and the chat screen by hand, then run this and type a question.
# Driving the UI over `adb shell input` proved fragile because the launcher can steal focus.
param(
    [string]$Serial = 'RZCY90QZFRD',
    [int]$WaitSeconds = 240
)
$adb = 'D:\Android_SDK\platform-tools\adb.exe'
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\Jaagruk-Mobile'
$out = "$d\_gen.txt"

& $adb -s $Serial shell svc power stayon usb | Out-Null
& $adb -s $Serial logcat -c | Out-Null

Write-Host "Ask a question in the app now. Watching logcat for up to $WaitSeconds s..."
for ($i = 0; $i -lt $WaitSeconds; $i += 10) {
    Start-Sleep -Seconds 10
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    if (& $adb -s $Serial logcat -d -s AIRepository:V | Select-String 'generated \d+ tokens') { break }
}

"=== load and generation ===" | Out-File $out -Encoding utf8
& $adb -s $Serial logcat -d -v brief -s AIRepository:V JaagrukLlm:V | Out-File $out -Append -Encoding utf8
Get-Content $out
