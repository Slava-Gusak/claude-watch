# Installs the claude-watch agent: copies files, adds Claude Code hooks, starts at logon.
$ErrorActionPreference = "Stop"
$dest = Join-Path $env:LOCALAPPDATA "ClaudeWatch"
New-Item -ItemType Directory -Force -Path $dest, (Join-Path $dest "sessions") | Out-Null
Copy-Item (Join-Path $PSScriptRoot "agent.ps1"), (Join-Path $PSScriptRoot "hook.ps1") $dest -Force

$cfgPath = Join-Path $dest "config.json"
if (-not (Test-Path $cfgPath)) {
    Write-Host "Открой на телефоне страницу /admin на VPS, скопируй строку из блока «Для ПК» и вставь сюда:" -ForegroundColor Cyan
    $line = (Read-Host "Строка").Trim()
    $url, $token = $line -split "\|", 2
    if (-not $token) { throw "Ожидалась строка вида https://адрес:порт|токен" }
    @{ url = $url.TrimEnd("/"); token = $token } | ConvertTo-Json | Set-Content $cfgPath -Encoding UTF8
}

# --- Claude Code hooks (~/.claude/settings.json), with a backup
$claudeDir = Join-Path $env:USERPROFILE ".claude"
New-Item -ItemType Directory -Force -Path $claudeDir | Out-Null
$settingsPath = Join-Path $claudeDir "settings.json"
if (Test-Path $settingsPath) {
    Copy-Item $settingsPath "$settingsPath.bak-claudewatch" -Force
    $settings = Get-Content $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
} else { $settings = [pscustomobject]@{} }
if (-not $settings.PSObject.Properties["hooks"]) { $settings | Add-Member hooks ([pscustomobject]@{}) }

$hookPs1 = (Join-Path $dest "hook.ps1") -replace "\\", "/"
$map = [ordered]@{
    SessionStart = "start"; UserPromptSubmit = "working"; PostToolUse = "working"
    Notification = "notify"; Stop = "idle"; SessionEnd = "end"
}
foreach ($evt in $map.Keys) {
    $cmd = "powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$hookPs1`" $($map[$evt])"
    $existing = @()
    if ($settings.hooks.PSObject.Properties[$evt]) {
        # drop our old entries, keep everything else the user has
        $existing = @($settings.hooks.$evt | Where-Object { -not (($_.hooks | ForEach-Object { $_.command }) -match "ClaudeWatch") })
        $settings.hooks.PSObject.Properties.Remove($evt)
    }
    $entry = [pscustomobject]@{ hooks = @([pscustomobject]@{ type = "command"; command = $cmd; timeout = 10 }) }
    if ($evt -eq "PostToolUse") { $entry | Add-Member matcher "*" }
    $settings.hooks | Add-Member $evt (@($existing) + $entry)
}
$json = $settings | ConvertTo-Json -Depth 30
[IO.File]::WriteAllText($settingsPath, $json, (New-Object Text.UTF8Encoding $false))

# --- autostart at logon + start now
$agent = Join-Path $dest "agent.ps1"
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$agent`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$opts = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "ClaudeWatchAgent" -Action $action -Trigger $trigger -Settings $opts -Force | Out-Null
Stop-ScheduledTask -TaskName "ClaudeWatchAgent" -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName "ClaudeWatchAgent"

Write-Host "`nГотово: агент запущен и будет стартовать при входе в Windows." -ForegroundColor Green
Write-Host "Уже открытые сессии Claude Code начнут слать статус после перезапуска."
Read-Host "Enter — закрыть"
