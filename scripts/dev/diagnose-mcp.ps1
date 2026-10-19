# Run this in a SECOND PowerShell window while `core` is running (started via
# .\run-core.ps1, which now tees its console output to core\core.log).
# Standing rule: never ask for manual copy/paste of console output - this
# script does the grepping and prints a plain summary instead.
#
# Usage: .\scripts\dev\diagnose-mcp.ps1   (works from anywhere - the log
# path below is resolved relative to this script, not the current directory)

# This script lives in scripts\dev\ now - core\core.log is two levels up at
# the repo root (the script sat at the root before the 2026-08-17 cleanup).
$logPath = Join-Path $PSScriptRoot "..\..\core\core.log"

if (-not (Test-Path $logPath)) {
    Write-Host "core\core.log not found - start core first with .\run-core.ps1 (it now writes this file automatically)." -ForegroundColor Red
    exit 1
}

Write-Host "=== Active Spring profiles ===" -ForegroundColor Cyan
$profileLines = Select-String -Path $logPath -Pattern "profile.*active|no active profile" -CaseSensitive:$false
if ($profileLines) {
    $profileLines | ForEach-Object { Write-Host $_.Line }
} else {
    Write-Host "(no match yet - core may still be starting; re-run this script in a few seconds)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== MCP / Puppeteer related lines ===" -ForegroundColor Cyan
$mcpLines = Select-String -Path $logPath -Pattern "mcp|puppeteer|McpSyncClient|McpAsyncClient|StdioClientTransport|MCP tool discovery failed" -CaseSensitive:$false
if ($mcpLines) {
    $mcpLines | ForEach-Object { Write-Host $_.Line }
} else {
    Write-Host "(no mcp/puppeteer-related lines found in the log yet)" -ForegroundColor Yellow
}

# The previous version above only prints lines matching a keyword - a
# multi-line Java stack trace's FIRST line (the actual exception class +
# message, e.g. "java.io.IOException: Cannot run program npx.cmd...") often
# doesn't contain any of those keywords and was getting silently dropped,
# which is exactly the line needed to know the real root cause. This grabs
# real context (20 lines after) around every "MCP tool discovery failed"
# occurrence instead, so the actual exception type/message is included.
Write-Host ""
Write-Host "=== Full stack trace(s) for 'MCP tool discovery failed' (root cause) ===" -ForegroundColor Cyan
$discoveryFailures = Select-String -Path $logPath -Pattern "MCP tool discovery failed" -Context 0,20
if ($discoveryFailures) {
    $discoveryFailures | Select-Object -Last 1 | ForEach-Object {
        Write-Host $_.Line
        $_.Context.PostContext | ForEach-Object { Write-Host $_ }
    }
} else {
    Write-Host "(none found yet)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== Any ERROR/WARN lines ===" -ForegroundColor Cyan
$errorLines = Select-String -Path $logPath -Pattern "ERROR|WARN" -CaseSensitive:$false
if ($errorLines) {
    $errorLines | Select-Object -Last 40 | ForEach-Object { Write-Host $_.Line }
} else {
    Write-Host "(none found)" -ForegroundColor Green
}
