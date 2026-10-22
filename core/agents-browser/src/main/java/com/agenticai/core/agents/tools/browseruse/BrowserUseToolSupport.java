package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Small shared helpers for the five {@code browser_*} tool callbacks - JSON result/error shaping
 * and tenant resolution - the browser-use twin of {@code
 * com.agenticai.core.agents.tools.device.DeviceToolSupport}, which documents the same split
 * rationale (each callback's {@code call()} body only handles its own arguments and result shape).
 */
final class BrowserUseToolSupport {

    static final ObjectMapper MAPPER = JsonMappers.plain();

    private BrowserUseToolSupport() {}

    /**
     * The tenant a browser-use session belongs to. Every {@code browser_*} tool is declared {@code
     * needsToolContext = true} in {@link
     * com.agenticai.core.agents.tools.classification.BuiltInToolClassifications} precisely so this
     * is never absent on a real call path - see {@code AgentToolProvider#guardAndLog}, whose {@code
     * TenantContextPropagatingToolCallback} wrapper re-establishes this ThreadLocal on whatever
     * thread actually invokes {@code call()}. Still returns {@link Optional#empty()} rather than
     * throwing, so a caller reached through some future context-free path (a background sweeper, a
     * test) degrades to a clear tool error instead of an NPE.
     */
    static Optional<String> resolveTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId);
    }

    static String noTenantContextError() {
        return errorJson(
                "Could not identify the current tenant for this browser session - try again from an active conversation.");
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return errorJson("Failed to serialize browser tool result: " + e.getMessage());
        }
    }

    /**
     * Same hand-built-JSON bug class {@code DeviceToolSupport#errorJson} fixed (a raw {@code
     * "{\"error\": \"" + message + "\"}"} concatenation breaks if {@code message} contains a quote,
     * which a Playwright exception message frequently does - it echoes the selector/URL back).
     */
    static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(toolInput, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
