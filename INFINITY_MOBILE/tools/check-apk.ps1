param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$BuildTools
)
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path -LiteralPath $Apk).Path
& (Join-Path $BuildTools 'apksigner.bat') verify --verbose $path
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $BuildTools 'zipalign.exe') -c -P 16 4 $path
if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment failed.' }

# Inspect ELF64 program headers directly, including dependencies already inside the APK.
$zip = [System.IO.Compression.ZipFile]::OpenRead($path)
$count = 0
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -match '^lib/(arm64-v8a|x86_64)/.+\.so$' }) {
        $buffer = [System.IO.MemoryStream]::new()
        $stream = $entry.Open()
        try { $stream.CopyTo($buffer) } finally { $stream.Dispose() }
        $reader = [System.IO.BinaryReader]::new($buffer)
        try {
            $buffer.Position = 0
            if ($reader.ReadUInt32() -ne 0x464C457F -or $reader.ReadByte() -ne 2 -or $reader.ReadByte() -ne 1) {
                throw "Unsupported ELF format: $($entry.FullName)"
            }
            $buffer.Position = 32
            $headerOffset = $reader.ReadUInt64()
            $buffer.Position = 54
            $headerSize = $reader.ReadUInt16()
            $headerCount = $reader.ReadUInt16()
            $loads = 0
            for ($i = 0; $i -lt $headerCount; $i++) {
                $offset = $headerOffset + $i * $headerSize
                $buffer.Position = $offset
                if ($reader.ReadUInt32() -ne 1) { continue }
                $loads++
                $buffer.Position = $offset + 48
                $alignment = $reader.ReadUInt64()
                if ($alignment -lt 16384) { throw "Not 16 KB aligned: $($entry.FullName), $alignment" }
            }
            if ($loads -eq 0) { throw "No loadable segments: $($entry.FullName)" }
            $count++
            "PASS ELF 16 KB: $($entry.FullName)"
        } finally { $reader.Dispose(); $buffer.Dispose() }
    }
} finally { $zip.Dispose() }
if ($count -eq 0) { throw 'No native libraries were checked.' }
"Verified $count native libraries."
Get-FileHash -LiteralPath $path -Algorithm SHA256
