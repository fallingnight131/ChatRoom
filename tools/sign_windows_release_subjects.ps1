[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidateSet("Payload", "Installer")][string]$Mode,
  [Parameter()][string]$ClientPath,
  [Parameter()][string]$LauncherPath,
  [Parameter()][string]$InstallerPath,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')]
  [string]$CertificateSha1,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{64}$')]
  [string]$ExpectedCertificateSha256,
  [Parameter(Mandatory = $true)][ValidatePattern('^https://[^/?#]+/[^?#]+$')]
  [string]$TimestampUrl
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-SigningSubject([string]$Path, [string]$ExpectedName) {
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.PSIsContainer -or $item.Name -cne $ExpectedName `
      -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw "Windows signing subject is unsafe or has an unexpected name: $ExpectedName"
  }
  $signature = Get-AuthenticodeSignature -LiteralPath $item.FullName
  if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::NotSigned) {
    throw "Windows signing subject must be unsigned before mutation: $ExpectedName"
  }
  return $item
}

function Certificate-Sha256(
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate) {
  return $Certificate.GetCertHashString(
    [System.Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
}

$subjects = @()
if ($Mode -eq "Payload") {
  if (-not $ClientPath -or -not $LauncherPath -or $InstallerPath) {
    throw "Payload signing requires only client and launcher paths"
  }
  $subjects = @(
    Resolve-SigningSubject $ClientPath "ChatClient.exe"
    Resolve-SigningSubject $LauncherPath "ChatRoomUpdateLauncher.exe"
  )
} else {
  if (-not $InstallerPath -or $ClientPath -or $LauncherPath) {
    throw "Installer signing requires only the installer path"
  }
  $installerName = [IO.Path]::GetFileName($InstallerPath)
  $installer = Resolve-SigningSubject $InstallerPath $installerName
  if ($installer.Name -notmatch '^ChatRoom-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-Setup\.exe$') {
    throw "Windows installer signing subject name is invalid"
  }
  $subjects = @($installer)
}

$matches = @(Get-ChildItem Cert:\LocalMachine\My | Where-Object {
  $_.Thumbprint.ToLowerInvariant() -ceq $CertificateSha1
})
if ($matches.Count -ne 1) { throw "Expected one machine-store signing certificate" }
$certificate = $matches[0]
$now = [DateTime]::UtcNow
$codeSigningOid = "1.3.6.1.5.5.7.3.3"
$hasCodeSigningEku = @($certificate.Extensions | Where-Object {
  $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]
} | ForEach-Object { $_.EnhancedKeyUsages } | Where-Object {
  $_.Value -eq $codeSigningOid
}).Count -gt 0
if (-not $certificate.HasPrivateKey -or -not $hasCodeSigningEku `
    -or $certificate.NotBefore.ToUniversalTime() -gt $now `
    -or $certificate.NotAfter.ToUniversalTime() -le $now `
    -or (Certificate-Sha256 $certificate) -cne $ExpectedCertificateSha256) {
  throw "Machine-store signing certificate policy rejected the certificate"
}

$signtool = (Get-Command signtool.exe -CommandType Application -ErrorAction Stop).Source
foreach ($subject in $subjects) {
  & $signtool sign /sha1 $CertificateSha1 /sm /s My /fd SHA256 `
    /tr $TimestampUrl /td SHA256 /v $subject.FullName
  if ($LASTEXITCODE -ne 0) {
    throw "signtool failed for $($subject.Name) with exit code $LASTEXITCODE"
  }
}

Write-Output "Windows release subjects signed and timestamped"
