# Reads a GGUF header and prints the metadata that identifies the model.
# Used to confirm a downloaded file is the model it claims to be before it is
# pushed to a handset, rather than finding out from a native crash.
param([Parameter(Mandatory = $true)][string]$Path)

$fs = [System.IO.File]::OpenRead($Path)
$br = New-Object System.IO.BinaryReader($fs)

function Read-Str($r) {
    $len = $r.ReadUInt64()
    [System.Text.Encoding]::UTF8.GetString($r.ReadBytes([int]$len))
}

# Skips a metadata value of any GGUF type, returning a printable form for scalars.
function Read-Val($r, $type) {
    switch ($type) {
        0  { return $r.ReadByte() }
        1  { return $r.ReadSByte() }
        2  { return $r.ReadUInt16() }
        3  { return $r.ReadInt16() }
        4  { return $r.ReadUInt32() }
        5  { return $r.ReadInt32() }
        6  { return $r.ReadSingle() }
        7  { return [bool]$r.ReadByte() }
        8  { return (Read-Str $r) }
        9  {
            $et = $r.ReadUInt32(); $n = $r.ReadUInt64()
            for ($i = 0; $i -lt [int]$n; $i++) { Read-Val $r $et | Out-Null }
            return "<array of $n>"
        }
        10 { return $r.ReadUInt64() }
        11 { return $r.ReadInt64() }
        12 { return $r.ReadDouble() }
        default { throw "unknown GGUF value type $type" }
    }
}

$magic = [System.Text.Encoding]::ASCII.GetString($br.ReadBytes(4))
$version = $br.ReadUInt32()
$tensorCount = $br.ReadUInt64()
$kvCount = $br.ReadUInt64()

$size = (Get-Item $Path).Length
Write-Output "file          : $(Split-Path $Path -Leaf)"
Write-Output "bytes         : $size  ($([math]::Round($size/1MB,2)) MB / $([math]::Round($size/1GB,3)) GiB)"
Write-Output "magic         : $magic"
Write-Output "gguf version  : $version"
Write-Output "tensor count  : $tensorCount"
Write-Output "metadata keys : $kvCount"
Write-Output "---- metadata ----"

$want = @(
    'general.architecture', 'general.name', 'general.size_label', 'general.file_type',
    'general.basename', 'general.quantization_version',
    'gemma3.block_count', 'gemma3.context_length', 'gemma3.embedding_length',
    'gemma3.attention.head_count', 'gemma3.attention.head_count_kv',
    'gemma3.vocab_size',
    'tokenizer.ggml.model', 'tokenizer.chat_template'
)

for ($i = 0; $i -lt [int]$kvCount; $i++) {
    $key = Read-Str $br
    $type = $br.ReadUInt32()
    $val = Read-Val $br $type
    if ($want -contains $key) {
        if ($key -eq 'tokenizer.chat_template') {
            Write-Output ("{0,-34}: {1}" -f $key, ($val -replace "`n", '\n').Substring(0, [Math]::Min(70, $val.Length)))
        } else {
            Write-Output ("{0,-34}: {1}" -f $key, $val)
        }
    }
}

$br.Close(); $fs.Close()
