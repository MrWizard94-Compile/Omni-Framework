#Requires -Version 7
<#
.SYNOPSIS
  Write a deterministic SHA-256 checksum manifest for release artifacts.

.DESCRIPTION
  Walks -Path (default: .\dist), hashes every regular file, and writes
  SHA256SUMS.txt with sorted relative paths and lowercase hex digests.
  Re-running on the same tree produces a byte-identical manifest (SOUL §11).

.PARAMETER Path
  Directory of artifacts to checksum.

.PARAMETER OutFile
  Manifest path (default: <Path>\SHA256SUMS.txt).

.EXAMPLE
  .\scripts\checksum-release.ps1 -Path .\dist
#>
param(
    [Parameter()]
    [string] $Path = ".\dist",

    [Parameter()]
    [string] $OutFile = ""
)

$ErrorActionPreference = "Stop"

$root = (Resolve-Path -LiteralPath $Path).Path
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "Not a directory: $root"
}

if ([string]::IsNullOrWhiteSpace($OutFile)) {
    $OutFile = Join-Path $root "SHA256SUMS.txt"
}

$files = Get-ChildItem -LiteralPath $root -Recurse -File |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object { $_.FullName.Substring($root.Length).TrimStart('\', '/') }

$lines = foreach ($file in $files) {
    $rel = $file.FullName.Substring($root.Length).TrimStart('\', '/').Replace('\', '/')
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "{0}  {1}" -f $hash, $rel
}

# UTF-8 no BOM, LF endings — stable across Windows/Unix consumers.
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($OutFile, (($lines -join "`n") + "`n"), $utf8)
Write-Host "Wrote $($lines.Count) checksum(s) → $OutFile"
