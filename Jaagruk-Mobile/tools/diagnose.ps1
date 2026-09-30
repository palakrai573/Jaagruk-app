# Captures why the app is not starting. Writes everything to one file.
param([string]$Serial = 'RZCY90QZFRD', [string]$Package = 'org.jaagruk.safety')
$adb = 'D:\Android_SDK\platform-tools\adb.exe'
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\Jaagruk-Mobile'
$out = "$d\_diag.txt"

function Log($t) { $t | Out-File $out -Append -Encoding utf8 }

"=== WHAT IS INSTALLED ===" | Out-File $out -Encoding utf8
& $adb -s $Serial shell "pm list packages | grep -i jaagruk" | Out-File $out -Append -Encoding utf8

Log "=== APK PATHS (more than one base.apk means a split/stale install) ==="
& $adb -s $Serial shell "pm path $Package" | Out-File $out -Append -Encoding utf8

Log "=== IS THE MODEL STILL THERE? ==="
& $adb -s $Serial shell "run-as $Package ls -l files/models/ 2>&1" | Out-File $out -Append -Encoding utf8

Log "=== FREE SPACE ==="
& $adb -s $Serial shell "df -h /data | tail -1" | Out-File $out -Append -Encoding utf8

# Clean slate, then launch and watch.
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial logcat -c | Out-Null
Start-Sleep -Seconds 1

Log "=== LAUNCH ==="
& $adb -s $Serial shell "am start -W -n $Package/$Package.MainActivity" | Out-File $out -Append -Encoding utf8

Start-Sleep -Seconds 12
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
Start-Sleep -Seconds 8

Log "=== PROCESS ALIVE? ==="
& $adb -s $Serial shell "pidof $Package || echo DEAD" | Out-File $out -Append -Encoding utf8

Log "=== FATAL EXCEPTIONS / ANR / TOMBSTONES ==="
& $adb -s $Serial logcat -d -v brief *:E | Select-String -Pattern 'jaagruk|AndroidRuntime|FATAL|ActivityManager|DEBUG|libc|SIGSEGV|Compose' |
    Select-Object -First 80 | ForEach-Object { $_.Line } | Out-File $out -Append -Encoding utf8

Log "=== FULL CRASH BLOCK IF ANY ==="
& $adb -s $Serial logcat -d -v brief | Select-String -Pattern 'FATAL EXCEPTION' -Context 0,45 |
    ForEach-Object { $_.Line; $_.Context.PostContext } | Out-File $out -Append -Encoding utf8

Log "=== OUR OWN TAGS ==="
& $adb -s $Serial logcat -d -v brief -s JaagrukLlm:V AIRepository:V AndroidRuntime:V |
    Select-Object -First 40 | Out-File $out -Append -Encoding utf8
