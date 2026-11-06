# claude-watch PC agent: every 15 s sends the state of Claude Desktop and Claude Code to the relay.
$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$cfg = Get-Content (Join-Path $PSScriptRoot "config.json") -Raw | ConvertFrom-Json
$sessionsDir = Join-Path $PSScriptRoot "sessions"
$logFile = Join-Path $PSScriptRoot "agent.log"
$interval = 15
$lastCpu = $null

function Log($m) {
    $line = "{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $m
    Add-Content -Path $logFile -Value $line
    if ((Get-Item $logFile).Length -gt 512KB) { Move-Item $logFile "$logFile.old" -Force }
}

while ($true) {
    try {
        $procs = Get-CimInstance Win32_Process -Filter "Name='claude.exe' OR Name='node.exe' OR Name='Claude.exe'"
        $desktop = @($procs | Where-Object {
            $_.Name -ieq "claude.exe" -and $_.ExecutablePath -match "AnthropicClaude|WindowsApps|\\Programs\\Claude" })
        $codeProcs = @($procs | Where-Object {
            ($_.Name -ieq "claude.exe" -and $_.ExecutablePath -match "\\\.local\\bin|\\npm\\|claude-code") -or
            ($_.Name -ieq "node.exe" -and $_.CommandLine -match "claude-code") })

        # Claude Desktop: running + "busy" when its processes burn noticeable CPU
        $busy = $false
        if ($desktop.Count -gt 0) {
            $cpu = 0.0
            foreach ($p in $desktop) {
                $gp = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
                if ($gp) { $cpu += $gp.TotalProcessorTime.TotalSeconds }
            }
            if ($null -ne $lastCpu) { $busy = (($cpu - $lastCpu) / $interval) -gt 0.08 }
            $lastCpu = $cpu
        } else { $lastCpu = $null }

        # Claude Code: per-session state written by hook.ps1
        $sessions = @()
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if (Test-Path $sessionsDir) {
            foreach ($f in Get-ChildItem $sessionsDir -Filter *.json) {
                try { $s = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { continue }
                if ($now - [int64]$s.ts -gt 12 * 3600 * 1000) { Remove-Item $f.FullName -Force; continue }
                $sessions += [ordered]@{ project = $s.project; state = $s.state; since = [int64]$s.ts }
            }
        }
        if ($codeProcs.Count -eq 0) { $sessions = @() }  # crashed/closed without SessionEnd
        $codeState = "off"
        if ($sessions | Where-Object { $_.state -eq "working" }) { $codeState = "working" }
        elseif ($sessions | Where-Object { $_.state -eq "waiting" }) { $codeState = "waiting" }
        elseif ($sessions.Count -gt 0 -or $codeProcs.Count -gt 0) { $codeState = "idle" }

        $body = [ordered]@{
            desktop = [ordered]@{ running = ($desktop.Count -gt 0); busy = $busy }
            code    = [ordered]@{ state = $codeState; sessions = @($sessions | Sort-Object { -$_.since } | Select-Object -First 5) }
        } | ConvertTo-Json -Depth 5 -Compress
        Invoke-RestMethod -Method Post -Uri "$($cfg.url)/pc" -TimeoutSec 15 `
            -Headers @{ Authorization = "Bearer $($cfg.token)" } `
            -ContentType "application/json; charset=utf-8" `
            -Body ([Text.Encoding]::UTF8.GetBytes($body)) | Out-Null
    } catch {
        Log "error: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $interval
}
