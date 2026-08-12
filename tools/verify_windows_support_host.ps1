[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$CurrentCandidateRoot,
  [Parameter(Mandatory = $true)][string]$PreviousCandidateRoot,
  [Parameter(Mandatory = $true)][ValidateSet("windows-10-22h2", "windows-11-23h2", "windows-11-24h2")][string]$TargetId,
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSignerSha256,
  [Parameter(Mandatory = $true)][string]$InstallRoot,
  [Parameter(Mandatory = $true)][string]$EvidencePath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$uninstallKey = "HKCU:/Software/Microsoft/Windows/CurrentVersion/Uninstall/ChatRoom"
$dataRoot = Join-Path $env:APPDATA "QtChatRoom/ChatClient"

function Require-RegularFile([string]$Path, [string]$Name) {
  $item = Get-Item -LiteralPath $Path -Force
  if ($item.PSIsContainer -or $item.Name -cne $Name `
      -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw "Windows support input is unsafe: $Name"
  }
  return $item
}

function Require-Signed([IO.FileInfo]$File) {
  $signature = Get-AuthenticodeSignature -LiteralPath $File.FullName
  if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid `
      -or -not $signature.SignerCertificate `
      -or -not $signature.TimeStamperCertificate) {
    throw "Windows support artifact is not valid timestamped Authenticode: $($File.Name)"
  }
  $digest = $signature.SignerCertificate.GetCertHashString(
    [Security.Cryptography.HashAlgorithmName]::SHA256).ToLowerInvariant()
  if ($digest -cne $ExpectedSignerSha256) {
    throw "Windows support artifact signer differs: $($File.Name)"
  }
}

function Start-And-RequireAlive([string]$Path, [string]$Label) {
  $process = Start-Process -FilePath $Path -PassThru
  Start-Sleep -Seconds 5
  if ($process.HasExited) { throw "$Label did not remain running" }
  return $process
}

function Stop-TestProcess([AllowNull()][Diagnostics.Process]$Process) {
  if ($null -ne $Process -and -not $Process.HasExited) {
    Stop-Process -Id $Process.Id -Force
    $Process.WaitForExit()
  }
}

function Require-SameSignedFile(
    [string]$InstalledPath, [string]$SourcePath, [string]$InstalledName,
    [string]$SourceName = "") {
  if (-not $SourceName) { $SourceName = $InstalledName }
  $installed = Require-RegularFile $InstalledPath $InstalledName
  $source = Require-RegularFile $SourcePath $SourceName
  if ($installed.Length -ne $source.Length `
      -or (Get-FileHash $installed.FullName -Algorithm SHA256).Hash `
          -cne (Get-FileHash $source.FullName -Algorithm SHA256).Hash) {
    throw "Installed Windows support artifact differs: $InstalledName"
  }
  Require-Signed $installed
  return $installed
}

$policy = Get-Content "packaging/windows/support-matrix-policy.json" -Raw | ConvertFrom-Json
$target = @($policy.targets | Where-Object { $_.targetId -ceq $TargetId })
if ($target.Count -ne 1) { throw "Windows support target is not uniquely configured" }
$os = Get-CimInstance Win32_OperatingSystem
$build = [int]$os.BuildNumber
if ([int]$os.ProductType -ne 1 -or $build -ne [int]$target[0].build `
    -or -not $os.Caption.Contains([string]$target[0].captionContains) `
    -or -not [Environment]::Is64BitOperatingSystem) {
  throw "Host is not the required x86_64 Windows client target $TargetId"
}

$currentManifestFile = Require-RegularFile `
  (Join-Path $CurrentCandidateRoot "windows-release-candidate.json") `
  "windows-release-candidate.json"
$previousManifestFile = Require-RegularFile `
  (Join-Path $PreviousCandidateRoot "windows-release-candidate.json") `
  "windows-release-candidate.json"
$current = Get-Content $currentManifestFile.FullName -Raw | ConvertFrom-Json
$previous = Get-Content $previousManifestFile.FullName -Raw | ConvertFrom-Json
if ($current.channel -cne $previous.channel -or $current.qtVersion -cne $previous.qtVersion `
    -or $current.expectedSignerCertificateSha256 -cne $ExpectedSignerSha256 `
    -or $previous.expectedSignerCertificateSha256 -cne $ExpectedSignerSha256 `
    -or [version]$current.version -le [version]$previous.version) {
  throw "Windows support candidate transition is invalid"
}

$currentSetup = Require-RegularFile `
  (Join-Path $CurrentCandidateRoot $current.installerPath) `
  "ChatRoom-$($current.version)-Setup.exe"
$previousSetup = Require-RegularFile `
  (Join-Path $PreviousCandidateRoot $previous.installerPath) `
  "ChatRoom-$($previous.version)-Setup.exe"
foreach ($file in @(
    (Require-RegularFile (Join-Path $CurrentCandidateRoot "client/ChatClient.exe") "ChatClient.exe"),
    (Require-RegularFile (Join-Path $CurrentCandidateRoot "client/ChatRoomUpdateLauncher.exe") "ChatRoomUpdateLauncher.exe"),
    (Require-RegularFile (Join-Path $CurrentCandidateRoot $current.uninstallerPath) "ChatRoom-$($current.version)-Uninstall.exe"),
    $currentSetup,
    (Require-RegularFile (Join-Path $PreviousCandidateRoot "client/ChatClient.exe") "ChatClient.exe"),
    (Require-RegularFile (Join-Path $PreviousCandidateRoot $previous.uninstallerPath) "ChatRoom-$($previous.version)-Uninstall.exe"),
    $previousSetup)) {
  Require-Signed $file
}

$root = [IO.Path]::GetFullPath($InstallRoot)
$evidence = [IO.FileInfo]::new($EvidencePath)
if (-not [IO.Path]::IsPathFullyQualified($root) -or $root.Contains(" ") `
    -or (Test-Path -LiteralPath $root) -or (Test-Path $uninstallKey) `
    -or (Test-Path -LiteralPath $dataRoot) -or [IO.File]::Exists($evidence.FullName)) {
  throw "Windows support host is not clean or evidence already exists"
}
$parent = Get-Item -LiteralPath ([IO.Path]::GetDirectoryName($root)) -Force
if (-not $parent.PSIsContainer `
    -or ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
  throw "Windows support install parent is unsafe"
}

$priorProcess = $null
$currentProcess = $null
$checks = [ordered]@{
  cleanHost = $true
  previousInstalled = $false
  previousLaunched = $false
  upgradeSucceeded = $false
  accountDataPreservedOnUpgrade = $false
  currentLaunched = $false
  runningClientUpgradeRejected = $false
  downgradeRejected = $false
  uninstallSucceeded = $false
  accountDataPreservedOnUninstall = $false
  programFilesRemoved = $false
  registrationRemoved = $false
}
try {
  $install = Start-Process $previousSetup.FullName -ArgumentList @("/S", "/D=$root") -Wait -PassThru
  if ($install.ExitCode -ne 0) { throw "Previous signed Setup install failed" }
  Require-SameSignedFile (Join-Path $root "ChatClient.exe") `
    (Join-Path $PreviousCandidateRoot "client/ChatClient.exe") "ChatClient.exe" | Out-Null
  $checks.previousInstalled = $true
  $priorProcess = Start-And-RequireAlive (Join-Path $root "ChatClient.exe") "Previous client"
  $checks.previousLaunched = $true
  Stop-TestProcess $priorProcess
  $priorProcess = $null

  New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
  $sentinel = Join-Path $dataRoot ".m4-supported-host-sentinel"
  [IO.File]::WriteAllText($sentinel, "preserve", [Text.UTF8Encoding]::new($false))
  $upgrade = Start-Process $currentSetup.FullName -ArgumentList @("/S", "/D=$root") -Wait -PassThru
  if ($upgrade.ExitCode -ne 0) { throw "Current signed Setup upgrade failed" }
  $checks.upgradeSucceeded = $true
  if ((Get-Content $sentinel -Raw) -cne "preserve") { throw "Upgrade changed account data" }
  $checks.accountDataPreservedOnUpgrade = $true
  $registration = Get-ItemProperty $uninstallKey
  if ($registration.DisplayVersion -cne $current.version `
      -or $registration.SourceRevision -cne $current.sourceRevision) {
    throw "Upgraded registration differs from current candidate"
  }
  $installedClient = Require-SameSignedFile (Join-Path $root "ChatClient.exe") `
    (Join-Path $CurrentCandidateRoot "client/ChatClient.exe") "ChatClient.exe"
  Require-SameSignedFile (Join-Path $root "ChatRoomUpdateLauncher.exe") `
    (Join-Path $CurrentCandidateRoot "client/ChatRoomUpdateLauncher.exe") `
    "ChatRoomUpdateLauncher.exe" | Out-Null
  Require-SameSignedFile (Join-Path $root "Uninstall.exe") `
    (Join-Path $CurrentCandidateRoot $current.uninstallerPath) `
    "Uninstall.exe" "ChatRoom-$($current.version)-Uninstall.exe" | Out-Null
  $currentProcess = Start-And-RequireAlive $installedClient.FullName "Current client"
  $checks.currentLaunched = $true
  $blocked = Start-Process $currentSetup.FullName -ArgumentList @("/S", "/D=$root") -Wait -PassThru
  if ($blocked.ExitCode -ne 4 -or $currentProcess.HasExited `
      -or (Get-Content $sentinel -Raw) -cne "preserve") {
    throw "Running-client upgrade was not rejected without mutation"
  }
  $checks.runningClientUpgradeRejected = $true
  Stop-TestProcess $currentProcess
  $currentProcess = $null

  $downgrade = Start-Process $previousSetup.FullName -ArgumentList @("/S", "/D=$root") -Wait -PassThru
  if ($downgrade.ExitCode -eq 0 `
      -or (Get-ItemProperty $uninstallKey).DisplayVersion -cne $current.version `
      -or (Get-Content $sentinel -Raw) -cne "preserve") {
    throw "Signed downgrade was not rejected without mutation"
  }
  $checks.downgradeRejected = $true
  $uninstaller = Require-RegularFile (Join-Path $root "Uninstall.exe") "Uninstall.exe"
  $uninstall = Start-Process $uninstaller.FullName -ArgumentList "/S" -Wait -PassThru
  if ($uninstall.ExitCode -ne 0) { throw "Current signed uninstall failed" }
  $checks.uninstallSucceeded = $true
  for ($attempt = 0; $attempt -lt 40 -and (Test-Path $root); $attempt++) {
    Start-Sleep -Milliseconds 250
  }
  if ((Get-Content $sentinel -Raw) -cne "preserve") { throw "Uninstall changed account data" }
  $checks.accountDataPreservedOnUninstall = $true
  if (Test-Path $root) { throw "Uninstall retained program files" }
  $checks.programFilesRemoved = $true
  if (Test-Path $uninstallKey) { throw "Uninstall retained registration" }
  $checks.registrationRemoved = $true

  $value = [ordered]@{
    schemaVersion = 1
    evidenceType = "windows-support-host-acceptance"
    status = "clean-install-upgrade-uninstall-observed"
    product = "chat-room-windows-client"
    targetId = $TargetId
    architecture = "x86_64"
    osCaption = [string]$os.Caption
    osVersion = [string]$os.Version
    osBuild = $build
    osProductType = [int]$os.ProductType
    currentVersion = [string]$current.version
    currentSourceRevision = [string]$current.sourceRevision
    previousVersion = [string]$previous.version
    previousSourceRevision = [string]$previous.sourceRevision
    channel = [string]$current.channel
    qtVersion = [string]$current.qtVersion
    expectedSignerCertificateSha256 = $ExpectedSignerSha256
    currentCandidateManifestSha256 = (Get-FileHash $currentManifestFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    previousCandidateManifestSha256 = (Get-FileHash $previousManifestFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    checks = $checks
    observedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
  }
  $evidence.Directory.Create()
  $temporary = Join-Path $evidence.DirectoryName (".support-{0}.tmp" -f [guid]::NewGuid().ToString("N"))
  [IO.File]::WriteAllText($temporary, ($value | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temporary -Destination $evidence.FullName
} finally {
  Stop-TestProcess $priorProcess
  Stop-TestProcess $currentProcess
  if (Test-Path $uninstallKey) {
    $installedUninstaller = Join-Path $root "Uninstall.exe"
    if (Test-Path $installedUninstaller -PathType Leaf) {
      Start-Process $installedUninstaller -ArgumentList "/S" -Wait | Out-Null
    }
  }
  if (Test-Path -LiteralPath $dataRoot) {
    Remove-Item -LiteralPath $dataRoot -Recurse -Force
  }
}
Write-Output "Windows support-host acceptance passed: $TargetId"
