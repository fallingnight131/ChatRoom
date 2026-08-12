[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$PayloadDirectory,
  [Parameter(Mandatory = $true)][string]$Version,
  [Parameter(Mandatory = $true)][string]$SourceRevision,
  [Parameter(Mandatory = $true)][string]$MakensisPath,
  [Parameter(Mandatory = $true)][string]$InstallerScript,
  [Parameter(Mandatory = $true)][string]$IconFile,
  [Parameter(Mandatory = $true)][string]$WorkRoot
)

$ErrorActionPreference = "Stop"

function Require-File([string]$Path, [string]$Description) {
  if (-not (Test-Path $Path -PathType Leaf)) { throw "$Description is missing" }
  return (Resolve-Path $Path).Path
}

if ($Version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
  throw "CMake installer version is invalid"
}
if ($SourceRevision -notmatch '^[0-9a-f]{40}([0-9a-f]{24})?$') {
  throw "CMake installer source revision is invalid"
}
$payload = (Resolve-Path $PayloadDirectory).Path
$makensis = Require-File $MakensisPath "Pinned NSIS compiler"
$nsi = Require-File $InstallerScript "NSIS policy script"
$icon = Require-File $IconFile "Windows product icon"
foreach ($required in @(
    "ChatClient.exe", "ChatRoomUpdateLauncher.exe", "sqldrivers/qsqlite.dll")) {
  Require-File (Join-Path $payload $required) "CMake payload $required" | Out-Null
}
if (-not (Get-ChildItem $payload -Filter "*sodium*.dll" -File)) {
  throw "CMake payload libsodium runtime is missing"
}

if (Test-Path $WorkRoot) { Remove-Item $WorkRoot -Recurse -Force }
New-Item -ItemType Directory -Force $WorkRoot | Out-Null
$output = Join-Path $WorkRoot "installer"
New-Item -ItemType Directory -Force $output | Out-Null
$installRoot = Join-Path $WorkRoot "install"
$sentinelDirectory = Join-Path $env:APPDATA "QtChatRoom/ChatClient"
New-Item -ItemType Directory -Force $sentinelDirectory | Out-Null
$sentinel = Join-Path $sentinelDirectory ".m4-cmake-installer-verification"
Set-Content -Path $sentinel -Value "preserve"

$clientProcess = $null
$launcherProcess = $null
$launcherParent = $null
$launcherReady = $null
$launcherCommit = $null
$launcherFixture = $null
$launcherResult = $null
try {
  & $makensis /WX /NOCONFIG /V2 "/DVERSION=$Version" `
    "/DSOURCE_REVISION=$SourceRevision" "/DPAYLOAD_DIR=$payload" `
    "/DOUTPUT_DIR=$output" "/DICON_FILE=$icon" $nsi
  if ($LASTEXITCODE -ne 0) { throw "CMake payload NSIS compilation failed" }
  $setup = Require-File `
    (Join-Path $output "ChatRoom-$Version-unsigned-verification-Setup.exe") `
    "CMake payload verification installer"
  if ((Get-AuthenticodeSignature $setup).Status -ne "NotSigned") {
    throw "CMake payload verification installer must remain unsigned"
  }

  $install = Start-Process -FilePath $setup `
    -ArgumentList @("/S", "/D=$installRoot") -Wait -PassThru
  if ($install.ExitCode -ne 0) {
    throw "CMake payload silent install failed with exit code $($install.ExitCode)"
  }
  $client = Require-File (Join-Path $installRoot "ChatClient.exe") "Installed CMake client"
  $launcher = Require-File `
    (Join-Path $installRoot "ChatRoomUpdateLauncher.exe") "Installed CMake update helper"
  Require-File (Join-Path $installRoot "sqldrivers/qsqlite.dll") `
    "Installed CMake SQLite driver" | Out-Null
  if (-not (Get-ChildItem $installRoot -Filter "*sodium*.dll" -File)) {
    throw "Installed CMake payload is missing libsodium"
  }
  if (-not (Get-Item $client).VersionInfo.ProductVersion.StartsWith($Version)) {
    throw "Installed CMake client version is not canonical"
  }
  if (-not (Get-Item $launcher).VersionInfo.ProductVersion.StartsWith($Version)) {
    throw "Installed CMake update helper version is not canonical"
  }

  $clientProcess = Start-Process -FilePath $client -PassThru
  Start-Sleep -Seconds 2
  if ($clientProcess.HasExited) { throw "Installed CMake client did not remain running" }
  Stop-Process -Id $clientProcess.Id -Force
  $clientProcess.WaitForExit()

  $launcherFixture = Join-Path $WorkRoot "unsigned-launcher-fixture.exe"
  Copy-Item $setup $launcherFixture
  $fixtureSize = (Get-Item $launcherFixture).Length
  $fixtureSha = (Get-FileHash $launcherFixture -Algorithm SHA256).Hash.ToLowerInvariant()
  $requestId = [guid]::NewGuid().ToString("D").ToLowerInvariant()
  $launcherResult = Join-Path $WorkRoot "result-$requestId.json"
  $readyName = "Local\ChatRoom.UpdateLauncher.Ready.$requestId"
  $commitName = "Local\ChatRoom.UpdateLauncher.Commit.$requestId"
  $launcherReady = [System.Threading.EventWaitHandle]::new(
    $false, [System.Threading.EventResetMode]::AutoReset, $readyName)
  $launcherCommit = [System.Threading.EventWaitHandle]::new(
    $false, [System.Threading.EventResetMode]::AutoReset, $commitName)
  $launcherParent = Start-Process powershell `
    -ArgumentList @("-NoProfile", "-Command", "Start-Sleep -Seconds 120") -PassThru
  $launcherProcess = Start-Process -FilePath $launcher -ArgumentList @(
    "--parent-pid", "$($launcherParent.Id)",
    "--installer", $launcherFixture,
    "--installer-size", "$fixtureSize",
    "--installer-sha256", $fixtureSha,
    "--signer-thumbprint-sha256", ("0" * 64),
    "--restart-executable", $client,
    "--result-file", $launcherResult,
    "--request-id", $requestId,
    "--ready-event", $readyName,
    "--commit-event", $commitName) -PassThru
  if (-not $launcherReady.WaitOne([TimeSpan]::FromSeconds(15))) {
    throw "CMake update helper did not signal readiness"
  }
  $launcherCommit.Set() | Out-Null
  Stop-Process -Id $launcherParent.Id
  $launcherParent.WaitForExit()
  if (-not $launcherProcess.WaitForExit(30000)) {
    throw "CMake update helper unsigned rejection timed out"
  }
  if ($launcherProcess.ExitCode -ne 4) {
    throw "CMake update helper returned $($launcherProcess.ExitCode) instead of 4"
  }
  $result = Get-Content $launcherResult -Raw | ConvertFrom-Json
  if ($result.requestId -ne $requestId -or $result.outcome -ne "trust-rejected" `
      -or (Test-Path $launcherFixture)) {
    throw "CMake update helper did not reject and clean the unsigned fixture"
  }

  $uninstall = Start-Process -FilePath (Join-Path $installRoot "Uninstall.exe") `
    -ArgumentList "/S" -Wait -PassThru
  if ($uninstall.ExitCode -ne 0) {
    throw "CMake payload uninstall failed with exit code $($uninstall.ExitCode)"
  }
  for ($attempt = 0; $attempt -lt 20 -and (Test-Path $installRoot); $attempt++) {
    Start-Sleep -Milliseconds 250
  }
  if (Test-Path $installRoot) { throw "CMake payload uninstall left program files" }
  if (-not (Test-Path $sentinel -PathType Leaf)) {
    throw "CMake payload install/uninstall deleted account-local data"
  }
  Write-Host "Windows CMake installer verification passed"
} finally {
  if ($launcherReady) { $launcherReady.Dispose() }
  if ($launcherCommit) { $launcherCommit.Dispose() }
  foreach ($process in @($clientProcess, $launcherProcess, $launcherParent)) {
    if ($process -and -not $process.HasExited) {
      Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
  }
  if (Test-Path (Join-Path $installRoot "Uninstall.exe") -PathType Leaf) {
    Start-Process -FilePath (Join-Path $installRoot "Uninstall.exe") `
      -ArgumentList "/S" -Wait | Out-Null
  }
  @($launcherFixture, $launcherResult, $sentinel) | Where-Object { $_ } | ForEach-Object {
    Remove-Item $_ -Force -ErrorAction SilentlyContinue
  }
}
