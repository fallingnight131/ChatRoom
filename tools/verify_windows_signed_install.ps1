[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$ClientPath,
  [Parameter(Mandatory = $true)][string]$LauncherPath,
  [Parameter(Mandatory = $true)][string]$UninstallerPath,
  [Parameter(Mandatory = $true)][string]$InstallerPath,
  [Parameter(Mandatory = $true)][string]$InstallRoot,
  [Parameter(Mandatory = $true)][ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')][string]$Version,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$SourceRevision,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSignerSha256,
  [Parameter(Mandatory = $true)][string]$EvidencePath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$uninstallKey = "HKCU:/Software/Microsoft/Windows/CurrentVersion/Uninstall/ChatRoom"

function Resolve-RegularFile([string]$Path, [string]$ExpectedName) {
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.PSIsContainer -or $item.Name -cne $ExpectedName `
      -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
    throw "Windows install acceptance input is unsafe: $ExpectedName"
  }
  return $item
}

function Sha256([System.IO.FileInfo]$File) {
  return (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Entry([string]$Role, [System.IO.FileInfo]$File, [string]$Name) {
  return [ordered]@{ role = $Role; name = $Name; size = $File.Length; sha256 = (Sha256 $File) }
}

function Require-TrustedSignature([System.IO.FileInfo]$File, [string]$Role) {
  $signature = Get-AuthenticodeSignature -LiteralPath $File.FullName
  if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid `
      -or -not $signature.SignerCertificate `
      -or -not $signature.TimeStamperCertificate) {
    throw "Installed $Role is not valid timestamped Authenticode"
  }
  $sha256 = $signature.SignerCertificate.GetCertHashString(
    [System.Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
  if ($sha256 -cne $ExpectedSignerSha256) {
    throw "Installed $Role signer does not match the reviewed certificate"
  }
}

$client = Resolve-RegularFile $ClientPath "ChatClient.exe"
$launcher = Resolve-RegularFile $LauncherPath "ChatRoomUpdateLauncher.exe"
$uninstaller = Resolve-RegularFile $UninstallerPath "ChatRoom-$Version-Uninstall.exe"
$installer = Resolve-RegularFile $InstallerPath "ChatRoom-$Version-Setup.exe"
$root = [IO.Path]::GetFullPath($InstallRoot)
if (-not [IO.Path]::IsPathFullyQualified($root) -or $root.Contains(" ") `
    -or [IO.Directory]::Exists($root) -or [IO.File]::Exists($root) `
    -or (Test-Path $uninstallKey)) {
  throw "Windows native install acceptance requires a clean absolute install boundary"
}
$rootParent = Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($root)) -Force
if (-not $rootParent.PSIsContainer `
    -or ($rootParent.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
  throw "Windows native install acceptance parent is unsafe"
}
$evidenceFile = [IO.FileInfo]::new($EvidencePath)
if (-not $evidenceFile.DirectoryName -or $evidenceFile.Extension -cne ".json" `
    -or [IO.File]::Exists($evidenceFile.FullName)) {
  throw "Windows native install evidence destination is unsafe or already exists"
}

$install = Start-Process -FilePath $installer.FullName `
  -ArgumentList @("/S", "/D=$root") -Wait -PassThru
if ($install.ExitCode -ne 0) { throw "Signed Setup install failed with exit code $($install.ExitCode)" }
$installedClient = Resolve-RegularFile "$root/ChatClient.exe" "ChatClient.exe"
$installedLauncher = Resolve-RegularFile "$root/ChatRoomUpdateLauncher.exe" "ChatRoomUpdateLauncher.exe"
$installedUninstaller = Resolve-RegularFile "$root/Uninstall.exe" "Uninstall.exe"
foreach ($pair in @(
    [pscustomobject]@{ Source = $client; Installed = $installedClient; Role = "client" }
    [pscustomobject]@{ Source = $launcher; Installed = $installedLauncher; Role = "update-launcher" }
    [pscustomobject]@{ Source = $uninstaller; Installed = $installedUninstaller; Role = "uninstaller" })) {
  if ((Sha256 $pair.Source) -cne (Sha256 $pair.Installed) `
      -or $pair.Source.Length -ne $pair.Installed.Length) {
    throw "Installed $($pair.Role) bytes do not match the signed source"
  }
  Require-TrustedSignature $pair.Installed $pair.Role
}
$registration = Get-ItemProperty $uninstallKey
if ($registration.DisplayVersion -cne $Version `
    -or $registration.SourceRevision -cne $SourceRevision `
    -or [IO.Path]::GetFullPath($registration.InstallLocation) -cne $root) {
  throw "Installed Windows registration does not match the candidate"
}
$installedEntries = @(
  Entry "client" $installedClient "ChatClient.exe"
  Entry "update-launcher" $installedLauncher "ChatRoomUpdateLauncher.exe"
  Entry "uninstaller" $installedUninstaller "Uninstall.exe"
)
$uninstall = Start-Process -FilePath $installedUninstaller.FullName `
  -ArgumentList "/S" -Wait -PassThru
if ($uninstall.ExitCode -ne 0) { throw "Signed uninstaller failed with exit code $($uninstall.ExitCode)" }
if ([IO.Directory]::Exists($root) -or [IO.Directory]::Exists("$root.__chatroom_stage") `
    -or [IO.Directory]::Exists("$root.__chatroom_backup") -or (Test-Path $uninstallKey)) {
  throw "Signed uninstall left program files or registration behind"
}

$evidence = [ordered]@{
  schemaVersion = 1
  evidenceType = "windows-native-install-acceptance"
  status = "install-uninstall-observed"
  product = "chat-room-windows-client"
  version = $Version
  sourceRevision = $SourceRevision
  architecture = "x86_64"
  observedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
  expectedSignerCertificateSha256 = $ExpectedSignerSha256
  sourceArtifacts = @(
    Entry "client" $client "ChatClient.exe"
    Entry "update-launcher" $launcher "ChatRoomUpdateLauncher.exe"
    Entry "uninstaller" $uninstaller "ChatRoom-$Version-Uninstall.exe"
    Entry "installer" $installer "ChatRoom-$Version-Setup.exe"
  )
  installedArtifacts = $installedEntries
  installExitCode = $install.ExitCode
  uninstallExitCode = $uninstall.ExitCode
  registrationMatched = $true
  installRootRemoved = $true
  temporaryPathsRemoved = $true
  registrationRemoved = $true
}
$directory = Get-Item -LiteralPath $evidenceFile.DirectoryName -Force
if (-not $directory.PSIsContainer `
    -or ($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
  throw "Windows native install evidence directory is unsafe"
}
$temporary = Join-Path $directory.FullName (".windows-install-{0}.tmp" -f [guid]::NewGuid().ToString("N"))
try {
  [IO.File]::WriteAllText($temporary, ($evidence | ConvertTo-Json -Depth 5) + "`n",
                          [Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temporary -Destination $evidenceFile.FullName
} finally {
  Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
}
Write-Output "Windows signed install/uninstall acceptance passed"
