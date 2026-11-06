# Claude Code hook: records the state of each session for agent.ps1.
# Called from ~/.claude/settings.json hooks with one argument: working | idle | notify | start | end
param([string]$Event = "idle")
$ErrorActionPreference = "SilentlyContinue"
$dir = Join-Path $PSScriptRoot "sessions"
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$raw = [Console]::In.ReadToEnd()
try { $in = $raw | ConvertFrom-Json } catch { $in = $null }
$sid = if ($in -and $in.session_id) { [string]$in.session_id } else { "unknown" }
$sid = $sid -replace '[^\w\-]', '_'
$file = Join-Path $dir "$sid.json"

if ($Event -eq "end") { Remove-Item $file -Force; exit 0 }

$state = switch ($Event) {
    "working" { "working" }
    "start"   { "idle" }
    "notify"  { if ("$($in.message)" -match "permission|approve|разреш") { "waiting" } else { "idle" } }
    default   { "idle" }
}
$cwd = if ($in -and $in.cwd) { [string]$in.cwd } else { "" }
$project = if ($cwd) { Split-Path $cwd -Leaf } else { "" }

$obj = [ordered]@{ session = $sid; project = $project; state = $state; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
$json = $obj | ConvertTo-Json -Compress
[IO.File]::WriteAllText($file, $json, (New-Object Text.UTF8Encoding $false))
exit 0
