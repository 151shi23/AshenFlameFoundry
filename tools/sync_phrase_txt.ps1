# tools\sync_phrase_txt.ps1
# ---------------------------------------------------------------------------
# Turn the hand-written lists next to this script into the built-in library the
# app ships with:
#     phrase_inbox.txt      ->  app\src\main\assets\phrase\phrases.json      (items)
#     homophones_inbox.txt  ->  app\src\main\assets\phrase\homophones.json   (map)
#
# Rules (identical to the in-app importer, so both routes behave the same):
#     phrases    : one per line; '#' starts a comment; blank lines ignored;
#                  a literal \n inside a line becomes a real line break;
#                  duplicates are dropped
#     homophones : one pair per line; separator can be
#                  U+2192 arrow / U+21E2 arrow / -> / => / = / fullwidth = /
#                  fullwidth colon / Tab / |   (the leftmost one wins);
#                  same key on several lines -> the last line wins
#
# Usage
#     powershell -NoProfile -ExecutionPolicy Bypass -File "<repo>\neteasemc\tools\sync_phrase_txt.ps1"
#     ... -Only phrase        : sync phrase_inbox.txt only, leave the homophone txt alone
#     ... -Only homophones    : sync homophones_inbox.txt only
#     ... -DryRun             : parse and report only, write nothing
#
# Notes
#     * Reads UTF-8 (with or without BOM) and falls back to GBK, so txt files
#       saved by Notepad work either way.
#     * Writes UTF-8 without BOM (a BOM would break JSON parsing on Android).
#     * The first run backs the old json up to <name>.json.bak once.
#     * This file is deliberately pure ASCII: Windows PowerShell 5.1 reads
#       BOM-less script files as ANSI, which would garble literal CJK symbols.
# ---------------------------------------------------------------------------

param(
    [switch]$DryRun,
    [ValidateSet('all', 'phrase', 'homophones')]
    [string]$Only = 'all'
)

$ErrorActionPreference = 'Stop'

$q           = [string][char]34        # "
$arrow       = [string][char]0x2192    # rightwards arrow
$arrowAlt    = [string][char]0x21E2    # rightwards dashed arrow
$fullEq      = [string][char]0xFF1D    # fullwidth equals
$fullColon   = [string][char]0xFF1A    # fullwidth colon

$tools  = $PSScriptRoot
$assets = Join-Path $tools '..\app\src\main\assets\phrase'
$phraseTxt  = Join-Path $tools 'phrase_inbox.txt'
$homoTxt    = Join-Path $tools 'homophones_inbox.txt'
$phraseJson = Join-Path $assets 'phrases.json'
$homoJson   = Join-Path $assets 'homophones.json'

$utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$utf8NoBom  = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        return [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    }
    try {
        return $utf8Strict.GetString($bytes)
    }
    catch {
        return [System.Text.Encoding]::GetEncoding(936).GetString($bytes)   # 936 = GBK
    }
}

function Get-Lines([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        return @()
    }
    $t = Read-Text $path
    $t = $t.Replace("`r`n", "`n").Replace("`r", "`n")
    return , @($t.Split("`n"))
}

function Esc-Json([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($c in $s.ToCharArray()) {
        if ($c -eq [char]34) { [void]$sb.Append('\"') }
        elseif ($c -eq [char]92) { [void]$sb.Append('\\') }
        elseif ($c -eq "`n") { [void]$sb.Append('\n') }
        elseif ($c -eq "`r") { [void]$sb.Append('\r') }
        elseif ($c -eq "`t") { [void]$sb.Append('\t') }
        elseif ([int]$c -lt 32) { [void]$sb.Append('\u' + ([int]$c).ToString('x4')) }
        else { [void]$sb.Append($c) }
    }
    return $sb.ToString()
}

function Get-Meta([string]$path, [string]$fallbackNote) {
    $meta = @{ version = 1; note = $fallbackNote }
    if (Test-Path -LiteralPath $path) {
        try {
            $o = (Read-Text $path) | ConvertFrom-Json
            if ($null -ne $o.version) { $meta.version = [int]$o.version }
            if ($null -ne $o.note) { $meta.note = [string]$o.note }
        }
        catch { }
    }
    return $meta
}

function Build-PhraseJson([int]$version, [string]$note, $items) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('{' + "`n")
    [void]$sb.Append('  ' + $q + 'version' + $q + ': ' + $version + ',' + "`n")
    [void]$sb.Append('  ' + $q + 'note' + $q + ': ' + $q + (Esc-Json $note) + $q + ',' + "`n")
    [void]$sb.Append('  ' + $q + 'items' + $q + ': [' + "`n")
    for ($i = 0; $i -lt $items.Count; $i++) {
        $comma = ','
        if ($i -eq $items.Count - 1) { $comma = '' }
        [void]$sb.Append('    ' + $q + (Esc-Json $items[$i]) + $q + $comma + "`n")
    }
    [void]$sb.Append('  ]' + "`n" + '}' + "`n")
    return $sb.ToString()
}

function Build-HomoJson([int]$version, [string]$note, $pairs) {
    $keys = @($pairs.Keys)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('{' + "`n")
    [void]$sb.Append('  ' + $q + 'version' + $q + ': ' + $version + ',' + "`n")
    [void]$sb.Append('  ' + $q + 'note' + $q + ': ' + $q + (Esc-Json $note) + $q + ',' + "`n")
    [void]$sb.Append('  ' + $q + 'map' + $q + ': {' + "`n")
    for ($i = 0; $i -lt $keys.Count; $i++) {
        $comma = ','
        if ($i -eq $keys.Count - 1) { $comma = '' }
        $k = [string]$keys[$i]
        [void]$sb.Append('    ' + $q + (Esc-Json $k) + $q + ': ' + $q + (Esc-Json ([string]$pairs[$k])) + $q + $comma + "`n")
    }
    [void]$sb.Append('  }' + "`n" + '}' + "`n")
    return $sb.ToString()
}

function Save-Json([string]$path, [string]$text) {
    if ($DryRun) {
        Write-Host ('[dry-run] would write ' + $path)
        return
    }
    # 备份放 tools 目录，别放 assets：assets 里的东西会被整包打进 APK
    $bak = Join-Path $tools ((Split-Path $path -Leaf) + '.bak')
    if ((Test-Path -LiteralPath $path) -and -not (Test-Path -LiteralPath $bak)) {
        Copy-Item -LiteralPath $path -Destination $bak
    }
    [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
    Write-Host ('written  ' + $path)
}

if (-not (Test-Path -LiteralPath $assets) -and -not $DryRun) {
    New-Item -ItemType Directory -Force -Path $assets | Out-Null
}

# ------------------------------------------------------------------ phrases

if ($Only -eq 'homophones') {
    Write-Host 'phrases    : skipped (-Only homophones)'
}
else {

$items = New-Object System.Collections.Generic.List[string]
$seen = New-Object System.Collections.Generic.HashSet[string]
$dup = 0
foreach ($line in (Get-Lines $phraseTxt)) {
    $s = $line.Trim()
    if ($s.Length -eq 0) { continue }
    if ($s[0] -eq [char]35) { continue }                 # 35 = '#'
    $body = $s.Replace('\n', "`n").Trim()
    if ($body.Length -eq 0) { continue }
    if ($seen.Add($body)) { $items.Add($body) } else { $dup++ }
}
$pMeta = Get-Meta $phraseJson 'built-in phrase list'
Save-Json $phraseJson (Build-PhraseJson $pMeta.version $pMeta.note $items)
Write-Host ('phrases    : ' + $items.Count + ' kept, ' + $dup + ' duplicate line(s) dropped, source = ' + (Split-Path $phraseTxt -Leaf))

}

# ------------------------------------------------------------------ homophones

if ($Only -eq 'phrase') {
    Write-Host 'homophones : skipped (-Only phrase)'
}
else {

$seps = @($arrow, $arrowAlt, '->', '=>', $fullEq, '=', $fullColon, "`t", '|')
$pairs = New-Object System.Collections.Specialized.OrderedDictionary
$bad = 0
foreach ($line in (Get-Lines $homoTxt)) {
    $s = $line.Trim()
    if ($s.Length -eq 0) { continue }
    if ($s[0] -eq [char]35) { continue }
    $best = -1
    $hit = $null
    foreach ($sep in $seps) {
        $i = $s.IndexOf([string]$sep)
        if ($i -gt 0 -and ($best -lt 0 -or $i -lt $best)) { $best = $i; $hit = [string]$sep }
    }
    if ($null -eq $hit) { $bad++; continue }
    $from = $s.Substring(0, $best).Trim()
    $to = $s.Substring($best + $hit.Length).Trim()
    if ($from.Length -eq 0 -or $to.Length -eq 0) { $bad++; continue }
    $pairs[$from] = $to
}
$hMeta = Get-Meta $homoJson 'built-in homophone map'
Save-Json $homoJson (Build-HomoJson $hMeta.version $hMeta.note $pairs)
Write-Host ('homophones : ' + $pairs.Count + ' pair(s), ' + $bad + ' unusable line(s) skipped, source = ' + (Split-Path $homoTxt -Leaf))

}
