package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestPolicy;
import com.agenticai.core.agents.tools.web.HttpRequestSafetyConfig;
import com.agenticai.core.agents.tools.web.HttpRequestToolCallback;
import com.agenticai.core.agents.tools.web.SafeHttpRequestToolCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * Decides which {@code http_request} callbacks an agent is offered, from {@code
 * app.tools.http-request.*} ({@link HttpRequestSafetyConfig}). Lives in this package (not {@code
 * tools.web}) because the safe-mode callbacks are wrapped in the package-private {@link
 * SanitizedMcpToolCallback} - the platform's third-party-result sanitizer (control/ANSI strip, size
 * cap, argument tripwire) - so that an HTTP response is treated like any other untrusted tool
 * output. Extracted from {@link AgentIntegrationToolResolver} so that class gains one call, not
 * logic. The resolver still wraps each returned callback in {@code guardAndLog} (step limit,
 * approval gate incl. the classification confirmation gate, logging, step events).
 *
 * <ul>
 *   <li>no configuration bean, or {@code safe-mode} off: today's single {@link
 *       HttpRequestToolCallback};
 *   <li>{@code enabled=false}: nothing is offered;
 *   <li>{@code safe-mode} on: {@code http_request} (read) and, unless {@code
 *       write-tool-enabled=false}, {@code http_request_write}, each bound to this agent's {@link
 *       HttpRequestPolicy}.
 * </ul>
 */
final class HttpRequestToolOffer {

    private HttpRequestToolOffer() {}

    static List<ToolCallback> callbacks(HttpRequestExecutor executor, UUID agentId) {
        HttpRequestSafetyConfig config = executor.safetyConfig();
        if (config == null) {
            return List.of(new HttpRequestToolCallback(executor));
        }
        if (!config.isEnabled()) {
            return List.of();
        }
        if (!config.isSafeMode()) {
            return List.of(new HttpRequestToolCallback(executor));
        }
        // Methods are set per tool instance by the factories below; the empty set here is replaced.
        HttpRequestPolicy policy = config.policyFor(agentId, Set.of());
        List<ToolCallback> offered = new ArrayList<>();
        offered.add(
                new SanitizedMcpToolCallback(
                        SafeHttpRequestToolCallback.read(executor, policy), false));
        if (config.isWriteToolEnabled()) {
            offered.add(
                    new SanitizedMcpToolCallback(
                            SafeHttpRequestToolCallback.write(executor, policy), false));
        }
        return offered;
    }
}
