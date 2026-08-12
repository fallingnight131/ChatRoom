[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ClientPath,
    [Parameter(Mandatory = $true)][string]$LauncherPath,
    [Parameter(Mandatory = $true)][string]$InstallerPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$Version,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceRevision,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSignerSha256,
    [Parameter(Mandatory = $true)][string]$EvidencePath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-ReleaseFile {
    param([string]$Path, [string]$ExpectedName)
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or $item.Name -cne $ExpectedName `
            -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw "Windows release signature input is unsafe or has an unexpected name: $ExpectedName"
    }
    return $item
}

function Get-Sha256Thumbprint {
    param([System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate)
    return $Certificate.GetCertHashString(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
}

function Inspect-ReleaseSignature {
    param([System.IO.FileInfo]$File, [string]$Role)
    $signature = Get-AuthenticodeSignature -LiteralPath $File.FullName
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid `
            -or -not $signature.SignerCertificate) {
        throw "$Role has no valid Authenticode signature"
    }
    $signerSha256 = Get-Sha256Thumbprint $signature.SignerCertificate
    if ($signerSha256 -cne $ExpectedSignerSha256) {
        throw "$Role signer certificate does not match the reviewed SHA-256 thumbprint"
    }
    if (-not $signature.TimeStamperCertificate) {
        throw "$Role has no validated Authenticode timestamp certificate"
    }
    $timestampSha256 = Get-Sha256Thumbprint $signature.TimeStamperCertificate
    $digest = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    return [ordered]@{
        role = $Role
        name = $File.Name
        size = $File.Length
        sha256 = $digest
        signerCertificateSha256 = $signerSha256
        timestampCertificateSha256 = $timestampSha256
        signatureStatus = "valid-timestamped-authenticode"
    }
}

$client = Resolve-ReleaseFile $ClientPath "ChatClient.exe"
$launcher = Resolve-ReleaseFile $LauncherPath "ChatRoomUpdateLauncher.exe"
$installer = Resolve-ReleaseFile $InstallerPath "ChatRoom-$Version-Setup.exe"

$evidenceFile = [System.IO.FileInfo]::new($EvidencePath)
if (-not $evidenceFile.DirectoryName -or $evidenceFile.Extension -cne ".json" `
        -or [IO.File]::Exists($evidenceFile.FullName)) {
    throw "Windows release signature evidence path is invalid or already exists"
}
$evidenceDirectory = Get-Item -LiteralPath $evidenceFile.DirectoryName -Force
if (-not $evidenceDirectory.PSIsContainer `
        -or ($evidenceDirectory.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw "Windows release signature evidence directory is unsafe"
}

$artifacts = @(
    Inspect-ReleaseSignature $client "client"
    Inspect-ReleaseSignature $launcher "update-launcher"
    Inspect-ReleaseSignature $installer "installer"
)
$evidence = [ordered]@{
    schemaVersion = 1
    product = "chat-room-windows-client"
    version = $Version
    sourceRevision = $SourceRevision
    architecture = "x86_64"
    observedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    expectedSignerCertificateSha256 = $ExpectedSignerSha256
    artifacts = $artifacts
}

$temporaryPath = Join-Path $evidenceDirectory.FullName (
    ".windows-release-signatures-{0}.tmp" -f [guid]::NewGuid().ToString("N"))
try {
    $json = $evidence | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText($temporaryPath, $json + "`n",
                                   [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $evidenceFile.FullName
} finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
}

Write-Output "Windows release signatures verified: version=$Version revision=$SourceRevision"
