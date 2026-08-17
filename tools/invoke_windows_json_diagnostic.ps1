function Invoke-ChatRoomWindowsJsonDiagnostic {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,

        [Parameter(Mandatory = $true)]
        [ValidateSet(
            "--chatroom-print-update-trust-json",
            "--chatroom-print-v2-configuration-json")]
        [string]$Argument
    )

    $resolvedExecutable = (Resolve-Path -LiteralPath $Executable -ErrorAction Stop).Path
    $stdoutPath = Join-Path ([IO.Path]::GetTempPath()) `
        ("chat-room-windows-diagnostic-{0}.json" -f [Guid]::NewGuid().ToString("N"))
    try {
        $process = Start-Process -FilePath $resolvedExecutable `
            -ArgumentList @($Argument) -RedirectStandardOutput $stdoutPath `
            -NoNewWindow -Wait -PassThru
        if ($process.ExitCode -ne 0) {
            throw "Windows JSON diagnostic failed with exit code $($process.ExitCode): $Argument"
        }
        $output = [IO.File]::ReadAllText($stdoutPath)
        if ([string]::IsNullOrWhiteSpace($output)) {
            throw "Windows JSON diagnostic returned empty output: $Argument"
        }
        return $output
    } finally {
        Remove-Item -LiteralPath $stdoutPath -Force -ErrorAction SilentlyContinue
    }
}
