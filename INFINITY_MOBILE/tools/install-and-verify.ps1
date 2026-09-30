# Installs the bundled build and confirms it starts and loads the model.
#
# Use this rather than `connectedAndroidTest` when the goal is a working app on the phone:
# the connected-test task installs, runs, and then UNINSTALLS both APKs, so interrupting it
# leaves the handset with no app and a dead launcher icon.
param([string]$Serial = 'RZCY90QZFRD', [string]$Package = 'org.jaagruk.safety')
$adb = 'D:\Android_SDK\platform-tools\adb.exe'
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$apk = "$d\app\build\outputs\apk\bundled\debug\app-bundled-debug.apk"
$out = "$d\_install.txt"

function Log($t) { $t | Out-File $out -Append -Encoding utf8 }

"=== free space before ===" | Out-File $out -Encoding utf8
& $adb -s $Serial shell "df -h /data | tail -1" | Out-File $out -Append -Encoding utf8

# The instrumentation APK is left behind by an aborted test run and shares the package's
# test target; removing it avoids a stale-signature conflict on reinstall.
Log "=== removing any leftover test APK ==="
& $adb -s $Serial uninstall "$Package.test" 2>&1 | Out-File $out -Append -Encoding utf8

Log "=== installing $([math]::Round((Get-Item $apk).Length/1MB)) MB, this takes a few minutes over USB ==="
& $adb -s $Serial install -r -t $apk 2>&1 | Out-File $out -Append -Encoding utf8

Log "=== installed? ==="
& $adb -s $Serial shell "pm list packages | grep -i jaagruk" | Out-File $out -Append -Encoding utf8

& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $Serial shell svc power stayon usb | Out-Null
& $adb -s $Serial logcat -c | Out-Null

Log "=== launching ==="
& $adb -s $Serial shell "am start -W -n $Package/$Package.MainActivity" | Out-File $out -Append -Encoding utf8

# First launch extracts 769 MiB out of the APK before the model can load, so this waits
# generously rather than reporting a failure that is really a copy in progress.
for ($i = 0; $i -lt 150; $i += 10) {
    Start-Sleep -Seconds 10
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
    $ready = & $adb -s $Serial logcat -d -s AIRepository:V | Select-String 'model ready'
    if ($ready) { Log "model ready after about $($i + 10) s"; break }
}

Log "=== model file ==="
& $adb -s $Serial shell "run-as $Package ls -l files/models/ 2>&1" | Out-File $out -Append -Encoding utf8

Log "=== engine log ==="
& $adb -s $Serial logcat -d -v brief -s JaagrukLlm:V AIRepository:V | Out-File $out -Append -Encoding utf8

Log "=== any crash? ==="
$crash = & $adb -s $Serial logcat -d -v brief | Select-String -Pattern 'FATAL EXCEPTION' -Context 0,30
if ($crash) { $crash | ForEach-Object { $_.Line; $_.Context.PostContext } | Out-File $out -Append -Encoding utf8 }
else { Log "  none" }

Log "=== process ==="
& $adb -s $Serial shell "pidof $Package || echo DEAD" | Out-File $out -Append -Encoding utf8
