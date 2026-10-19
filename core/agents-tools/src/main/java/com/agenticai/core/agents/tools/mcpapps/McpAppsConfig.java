package com.agenticai.core.agents.tools.mcpapps;

import java.time.Duration;
import java.util.List;

/**
 * Effective {@code app.mcp.apps.*} settings. Every limit is a hard ceiling that fails closed: an
 * unusable value (zero, negative) falls back to the default rather than disabling the check.
 */
public record McpAppsConfig(
        int maxResourceBytes,
        Duration fetchTimeout,
        Duration cacheTtl,
        List<String> allowedDomains,
        boolean httpsImages,
        int maxResultChars,
        Duration sessionTtl,
        int maxSessions,
        int callsPerMinute,
        int maxConcurrentFetchesPerTenant,
        int maxArgsChars) {

    public static final int DEFAULT_MAX_RESOURCE_BYTES = 512 * 1024;

    public McpAppsConfig {
        maxResourceBytes = maxResourceBytes > 0 ? maxResourceBytes : DEFAULT_MAX_RESOURCE_BYTES;
        fetchTimeout =
                fetchTimeout == null || fetchTimeout.isZero() || fetchTimeout.isNegative()
                        ? Duration.ofSeconds(5)
                        : fetchTimeout;
        cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ofSeconds(60) : cacheTtl;
        allowedDomains = McpAppCsp.normalizeAllowlist(allowedDomains);
        maxResultChars = maxResultChars > 0 ? maxResultChars : 64 * 1024;
        sessionTtl =
                sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()
                        ? Duration.ofMinutes(15)
                        : sessionTtl;
        maxSessions = maxSessions > 0 ? maxSessions : 500;
        callsPerMinute = callsPerMinute > 0 ? callsPerMinute : 30;
        maxConcurrentFetchesPerTenant =
                maxConcurrentFetchesPerTenant > 0 ? maxConcurrentFetchesPerTenant : 4;
        maxArgsChars = maxArgsChars > 0 ? maxArgsChars : 32 * 1024;
    }

    public static McpAppsConfig defaults() {
        return new McpAppsConfig(0, null, null, List.of(), true, 0, null, 0, 0, 0, 0);
    }
}
