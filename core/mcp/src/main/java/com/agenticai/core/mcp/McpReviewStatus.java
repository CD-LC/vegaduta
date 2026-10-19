package com.agenticai.core.mcp;

/**
 * Platform-staff approval gate for self-service STDIO (process-spawning) MCP
 * registrations - the interim mitigation {@code docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md}
 * (option (d)) recommended after the pentest's Critical C1 finding concluded
 * that no string-matching/allowlist can close the "{@code npx <any-package>}
 * is full RCE inside the shared {@code core} container" gap: the only thing
 * that closes it today is a human looking at the command before it's ever
 * allowed to spawn.
 *
 * <p>Deliberately separate from {@link McpConnectionStatus} (the live
 * connection) and {@link McpUsageStatus} (has this ever passed a real
 * connect+initialize test) - this is a THIRD, independent gate: even a
 * registration that would otherwise pass every guard ({@link
 * McpStdioCommandGuard}, {@link McpRemoteUrlGuard}) and connect cleanly must
 * still never be allowed to spawn a process while {@link #PENDING} or {@link
 * #REJECTED}. See {@code DynamicMcpConnectionManager#connect}/{@code #test}
 * (core/agents) for the enforcement point - this check runs FIRST, before any
 * other guard.
 *
 * <p><b>Scope, precisely</b> (set once at {@link McpRegistryService#register}
 * time, based on server-verified provenance - never a client-supplied claim):
 * <ul>
 *   <li>Remote (SSE / streamable-HTTP) registrations - always {@link
 *       #NOT_REQUIRED}. Different threat model entirely: no code execution in
 *       this container, network-only, already SSRF-guarded ({@link
 *       McpRemoteUrlGuard}).</li>
 *   <li>STDIO registrations whose {@code name} matches a {@link
 *       McpCatalogService} entry - always {@link #NOT_REQUIRED}. The
 *       platform team already curated and vetted these launch commands; that
 *       IS the review.</li>
 *   <li>Every other STDIO registration - hand-typed by a tenant-admin, or
 *       added via the public "Browse registry" search - starts {@link
 *       #PENDING}. Both sources are equally unvetted (a registry listing
 *       proves namespace ownership, not safety) - see {@code
 *       McpCatalogService#isCatalogEntry}'s javadoc, the one existing signal
 *       this platform already trusted for a materially similar decision
 *       (FREE-tier custom-server gating).</li>
 * </ul>
 */
public enum McpReviewStatus {
    /** Not subject to this gate at all - remote registration, or a curated catalog entry. */
    NOT_REQUIRED,
    /** A self-service custom/registry-browsed STDIO registration awaiting platform-staff sign-off. Connect/test refused. */
    PENDING,
    /** Platform staff approved this exact command/args/env - future connect/test attempts proceed normally. */
    APPROVED,
    /** Platform staff rejected this registration - see {@code reviewRejectionReason}. Connect/test refused until re-edited. */
    REJECTED
}
