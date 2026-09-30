# Phase 1 gate / risk R1: does the vendored llama.cpp in :ai load a real GGUF on a real handset?
#
# 65 unit tests cover the orchestration around the engine, but every one of them runs against a
# fake or with no native library at all. This is the only check that exercises
# libjaagruk_llm.so against actual weights on actual hardware.
param(
    [string]$Serial = 'RZCY90QZFRD',
    [string]$Package = 'com.infinity.ai',
    [string]$Activity = 'com.infinity.ai.MainActivity',
    [int]$WaitSeconds = 75
)
$adb = 'D:\Android_SDK\platform-tools\adb.exe'
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\INFINITY_MOBILE'
$out = "$d\_r1.txt"

& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb -s $Serial shell svc power stayon usb | Out-Null
& $adb -s $Serial shell am force-stop $Package | Out-Null
& $adb -s $Serial logcat -c | Out-Null

"=== model file on device ===" | Out-File $out -Encoding utf8
& $adb -s $Serial shell "run-as $Package ls -l files/models/" | Out-File $out -Append -Encoding utf8

"=== launching $Activity ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell am start -n "$Package/$Activity" | Out-File $out -Append -Encoding utf8

# The model load happens off the main thread as soon as a view model initialises, so the
# interesting lines arrive over the following seconds rather than immediately.
for ($i = 0; $i -lt $WaitSeconds; $i += 5) {
    Start-Sleep -Seconds 5
    & $adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
}

"=== logcat: engine, repository, native ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial logcat -d -v brief `
    -s JaagrukLlm:V AIRepository:V LlamaEngine:V llama:V ggml:V libc:V DEBUG:V AndroidRuntime:E `
    | Out-File $out -Append -Encoding utf8

"=== logcat: anything from our process mentioning the model ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial logcat -d | Select-String -Pattern 'gguf|llama_|ggml_|Jaagruk|model|tokens' `
    | Select-Object -Last 60 | ForEach-Object { $_.Line } | Out-File $out -Append -Encoding utf8

"=== did the process survive? ===" | Out-File $out -Append -Encoding utf8
& $adb -s $Serial shell "pidof $Package || echo 'PROCESS NOT RUNNING'" | Out-File $out -Append -Encoding utf8
