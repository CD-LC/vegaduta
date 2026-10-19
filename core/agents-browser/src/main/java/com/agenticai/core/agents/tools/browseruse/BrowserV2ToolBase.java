package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.PlaywrightException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Shared shell of the browser agent v2 tool callbacks ({@code browser_snapshot}, {@code
 * browser_click_element}, ...): tenant resolution, tolerant argument parsing and the same "never
 * throws, every failure is a short JSON {@code {"error": "..."}} the model can relay" contract the
 * five original {@code browser_*} tools follow. Subclasses supply the definition and {@link
 * #execute}.
 *
 * <p>Identity never travels as an argument: the tenant comes from {@code TenantContext}
 * (re-established on the tool thread by the guard chain), the agent and session ids are fixed at
 * construction by the resolver. No result string may contain a resolved vault value - see {@link
 * BrowserSecretPlaceholders}.
 */
abstract class BrowserV2ToolBase implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BrowserV2ToolBase.class);

    protected final BrowserUseSessionManager sessionManager;
    protected final UUID agentId;
    protected final String sessionId;

    protected BrowserV2ToolBase(
            BrowserUseSessionManager sessionManager, UUID agentId, String sessionId) {
        this.sessionManager = sessionManager;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    /** The tool name the model calls. */
    abstract String toolName();

    /** Short, model-facing description (tool schemas are token-budgeted). */
    abstract String toolDescription();

    /** JSON schema of the arguments. */
    abstract String toolInputSchema();

    /**
     * Runs the tool; returns the result string. May throw {@link BrowserUseException} for a clean
     * failure.
     */
    abstract String execute(String tenantId, Map<String, Object> args) throws Exception;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(toolName())
                .description(toolDescription())
                .inputSchema(toolInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        Optional<String> tenantId = BrowserUseToolSupport.resolveTenantId();
        if (tenantId.isEmpty()) {
            return BrowserUseToolSupport.noTenantContextError();
        }
        Map<String, Object> args = BrowserUseToolSupport.parseArgs(toolInput);
        try {
            return execute(tenantId.get(), args);
        } catch (BrowserUseException e) {
            return BrowserUseToolSupport.errorJson(e.getMessage());
        } catch (PlaywrightException e) {
            // Deliberately generic: a Playwright message can quote selectors or typed text.
            return BrowserUseToolSupport.errorJson(
                    "The browser action failed - take a new browser_snapshot and try again.");
        } catch (Exception e) {
            log.warn(
                    "{} tool call failed unexpectedly: {}",
                    toolName(),
                    e.getClass().getSimpleName());
            return BrowserUseToolSupport.errorJson("The browser action failed unexpectedly.");
        }
    }

    /** The 1-based element index argument, or null when absent/not a whole number. */
    static Integer asIndex(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && d >= 1 && d <= 100_000 ? Integer.valueOf((int) d) : null;
        }
        try {
            int parsed = Integer.parseInt(value.toString().strip());
            return parsed >= 1 && parsed <= 100_000 ? Integer.valueOf(parsed) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String missingIndex() {
        return BrowserUseToolSupport.errorJson(
                "The 'index' argument is required: the number of an element from the latest "
                        + "browser_snapshot (1 or higher).");
    }
}
