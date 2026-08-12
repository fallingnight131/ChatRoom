[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$ManifestPath,
  [Parameter(Mandatory = $true)][string]$SignaturePath,
  [Parameter(Mandatory = $true)][string]$PublicKeyPath,
  [Parameter(Mandatory = $true)][ValidatePattern('^[a-z0-9][a-z0-9.-]{0,63}$')]
  [string]$ExpectedKeyId,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{64}$')]
  [string]$ExpectedPublicKeyFileSha256
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-RegularFile([string]$Path, [string]$Label) {
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.PSIsContainer `
      -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw "$Label must be a regular non-reparse file"
  }
  return $item
}

$manifest = Resolve-RegularFile $ManifestPath "Windows update manifest"
$publicKey = Resolve-RegularFile $PublicKeyPath "Windows update public key"
$signature = [IO.FileInfo]::new($SignaturePath)
if (-not $signature.DirectoryName -or [IO.File]::Exists($signature.FullName) `
    -or [IO.Directory]::Exists($signature.FullName) `
    -or $signature.FullName -in @($manifest.FullName, $publicKey.FullName)) {
  throw "Windows update signature destination is unsafe or already exists"
}
$signatureDirectory = Get-Item -LiteralPath $signature.DirectoryName -Force
if (-not $signatureDirectory.PSIsContainer `
    -or ($signatureDirectory.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
  throw "Windows update signature destination directory is unsafe"
}
$publicKeyDigest = (Get-FileHash -LiteralPath $publicKey.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($publicKeyDigest -cne $ExpectedPublicKeyFileSha256) {
  throw "Windows update public key file does not match the reviewed SHA-256"
}

$manifestIdentity = python tools/windows_update_manifest.py inspect `
  --manifest $manifest.FullName
if ($LASTEXITCODE -ne 0) { throw "Windows update manifest inspection failed" }
$identity = $manifestIdentity | ConvertFrom-Json
if ($identity.signingKeyId -cne $ExpectedKeyId) {
  throw "Windows update manifest key ID does not match the protected signer"
}

$keyUri = $env:CHATROOM_UPDATE_SIGNING_KEY_URI
if (-not $keyUri `
    -or $keyUri -cnotmatch '^pkcs11:[^\s?#]+(?:;[^\s?#]+)*$' `
    -or $keyUri -match '(?i)(pin-source|pin-value|password|secret)') {
  throw "Protected update signing key URI is absent or unsafe"
}
$openssl = (Get-Command openssl.exe -CommandType Application -ErrorAction Stop).Source
$version = (& $openssl version).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^OpenSSL 3\.') {
  throw "Protected update signing requires preinstalled OpenSSL 3"
}

$temporary = Join-Path $signatureDirectory.FullName (
  ".windows-update-signature-{0}.tmp" -f [guid]::NewGuid().ToString("N"))
try {
  & $openssl pkeyutl -sign -rawin -inkey $keyUri `
    -in $manifest.FullName -out $temporary
  if ($LASTEXITCODE -ne 0) { throw "Protected Ed25519 signing failed" }
  $temporaryFile = Resolve-RegularFile $temporary "Windows update temporary signature"
  if ($temporaryFile.Length -ne 64) { throw "Protected Ed25519 signature is not 64 bytes" }
  & $openssl pkeyutl -verify -pubin -rawin -inkey $publicKey.FullName `
    -in $manifest.FullName -sigfile $temporaryFile.FullName
  if ($LASTEXITCODE -ne 0) { throw "Protected Ed25519 signature verification failed" }
  [IO.File]::Move($temporaryFile.FullName, $signature.FullName)
} finally {
  Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
}

Write-Output "Protected Windows update manifest signed and independently verified"
