
# Root cause (core.log): the very first real connection to a static MCP
# server after a restart pays npm/npx's one-time "download this package"
# cost - and for @modelcontextprotocol/server-puppeteer specifically, that
# also triggers Puppeteer's own postinstall step, which downloads a full
# Chromium binary (100s of MB). That easily blows past the MCP SDK's
# connection-handshake timeout, which is why core logged:
#   "MCP tool discovery failed ... TimeoutException: Did not observe any
#   item or terminal signal within 20000ms"
# followed immediately by a second crash ("Sinks.many().unicast() sinks
# only allow a single Subscriber") from the SDK retrying against a
# half-open connection.
#
# core-side fix (already applied): a McpClientCustomizer bean now raises
# the SDK's connection-handshake timeout to 120s, and the tool-discovery
# cache is now concurrency-safe so two near-simultaneous requests can't
# both slam the same client at once. But neither of those makes a slow
# first-time download fast - this script does that PROACTIVELY, before
# core ever needs to connect for real, so by the time you retry the
# Puppeteer Quick Task, npm's local cache is already warm and the real
# connection is near-instant.
#
# Safe to run anytime core is stopped or running - this only starts/kills
# separate throwaway `npx` processes, it does not touch core's own process.
# Run from anywhere; takes ~2-5 minutes total (mostly the Puppeteer/Chromium
# download, if this machine has never run it before).

$packages = @(
    "@modelcontextprotocol/server-filesystem",
    "@modelcontextprotocol/server-memory",
    "@modelcontextprotocol/server-sequential-thinking",
    "@modelcontextprotocol/server-puppeteer"
)

foreach ($pkg in $packages) {
    Write-Host "=== Pre-warming $pkg ===" -ForegroundColor Cyan
    # These are MCP stdio servers - they wait on stdin forever once started,
    # they don't exit on their own. We only need the download+cache side
    # effect, so start it, give npm/npx up to 90s to finish downloading
    # (and, for puppeteer, downloading Chromium), then kill it regardless of
    # whether it "finished" - the cache is already populated as soon as the
    # download completes, well before the process would ever exit anyway.
    $proc = Start-Process -FilePath "npx.cmd" -ArgumentList "-y", $pkg -PassThru -WindowStyle Hidden
    $waited = 0
    while (-not $proc.HasExited -and $waited -lt 90) {
        Start-Sleep -Seconds 3
        $waited += 3
    }
    if (-not $proc.HasExited) {
        Write-Host "  Cache warm (or download still large) after ${waited}s - stopping throwaway process." -ForegroundColor Yellow
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "  Process exited on its own after ${waited}s (exit code $($proc.ExitCode))." -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Pre-warm pass complete. Now restart core (free port 8080 if needed, then .\run-core.ps1 from the repo root) and retry the Puppeteer Quick Task." -ForegroundColor Cyan
