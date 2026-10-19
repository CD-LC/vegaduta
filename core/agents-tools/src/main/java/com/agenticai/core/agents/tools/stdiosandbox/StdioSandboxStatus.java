package com.agenticai.core.agents.tools.stdiosandbox;

/**
 * What the status/doctor surface reports about STDIO sandboxing. Contains no path, secret or env
 * value.
 *
 * @param mode configured mode ({@code none}/{@code os}/{@code container})
 * @param mechanism what will confine spawns ({@code seatbelt}, {@code bubblewrap}, {@code
 *     unshare}, {@code docker}, {@code podman}), or {@code none}
 * @param available the mechanism works on this host (always true for mode {@code none})
 * @param degraded the mechanism does not confine the filesystem (Linux {@code unshare} fallback)
 * @param enforcing spawns are actually confined right now: mode is not none, the mechanism is
 *     available, or spawns are refused ({@code required})
 * @param required {@code app.mcp.stdio.sandbox-required}
 * @param networkAllowAll every server may use the network ({@code sandbox-network-allow} has {@code *})
 * @param networkAllowCount how many servers are named in {@code sandbox-network-allow} (the names
 *     themselves are not exposed: any authenticated user can read this endpoint)
 * @param detail a one-line, user-safe explanation and, when unavailable, the fix
 */
public record StdioSandboxStatus(
        String mode,
        String mechanism,
        boolean available,
        boolean degraded,
        boolean enforcing,
        boolean required,
        boolean networkAllowAll,
        int networkAllowCount,
        String detail) {}
