# Runs a Gradle build and captures output to a file.
#
# Redirection happens INSIDE cmd rather than through a PowerShell pipe. Piping a
# daemonising process through PowerShell made Start-Process -Wait hang on inherited
# child handles long after Gradle itself had finished.
param([string]$Tasks = ':core:test', [string]$Tag = 'run')
$d = 'd:\Endeavors\Coding\Projects\Jaagruk - Kotlin\Jaagruk-Mobile'
$log = "$d\_$Tag.log"
if (Test-Path $log) { Remove-Item $log -Force }
cmd /c "cd /d ""$d"" && gradlew.bat $Tasks --console=plain > ""$log"" 2>&1"
"EXITCODE=$LASTEXITCODE" | Out-File "$d\_$Tag.exit" -Encoding ascii
