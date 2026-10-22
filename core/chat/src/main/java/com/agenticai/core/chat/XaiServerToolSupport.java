package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * xAI Agent Tools API support for the {@link com.agenticai.core.providers.ProviderName#GROK} path
 * (2026-09-23, per {@code docs/plans/INTEGRATION-RESEARCH-2026-09-23.md}).
 *
 * <p>xAI's OpenAI-compatible Chat Completions endpoint accepts SERVER-SIDE tools in the same {@code
 * tools} array as function tools: entries shaped {@code {"type":"web_search"}}, {@code
 * {"type":"x_search"}}, {@code {"type":"code_execution"}} (and {@code collections_search}/ {@code
 * mcp}, not yet enabled here). xAI executes those on its own infrastructure - web and X search
 * results, remote Python execution - and the model can invoke them across several turns before
 * producing its final answer, exactly like Grok does inside the X app. Unlike this platform's own
 * {@code web_search}/{@code run_code} ToolCallbacks (which run HERE and are governed by
 * guardrails/approvals), these run on xAI's side and are billed by xAI per tool invocation - which
 * is why the whole feature sits behind the per-agent {@code xaiServerToolsEnabled} opt-in
 * (migration V239) rather than being on for every Grok agent.
 *
 * <p><b>Why HTTP-layer injection:</b> the SDK-based OpenAI model (Spring AI 2) types {@code tools}
 * as function tools only - there is no options-level way to express a non-function tool entry.
 * Rather than forking the model/request types, the entries are appended to the outgoing request
 * body by an OkHttp interceptor on the shared LLM transport ({@link #inject}, wired by {@link
 * GrokAgentChatClientFactory} through {@code OpenAiCompatModelBuilder.Api#bodyTransform}), which
 * covers the blocking and the streaming call with one code path.
 */
public final class XaiServerToolSupport {

    /** {@code providerOptions} key on {@code GatewayCallSpec} carrying the enabled tool set. */
    public static final String OPTION_TOOLS = "xaiServerTools";

    /**
     * The standard trio exposed by the per-agent toggle today. {@code collections_search} needs
     * per-tenant collection ids and {@code mcp} a server URL - both deliberately left out until
     * there is a config surface for them (see the research doc).
     */
    public static final Set<String> DEFAULT_SERVER_TOOLS =
            Set.of("web_search", "x_search", "code_execution");

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private XaiServerToolSupport() {}

    /**
     * Reads {@link #OPTION_TOOLS} out of a {@code providerOptions} map. Accepts a {@code
     * Collection<String>} of tool types or the Boolean {@code true} (meaning {@link
     * #DEFAULT_SERVER_TOOLS}); anything else - including absent - resolves to an empty set, i.e.
     * the feature off, matching every other opt-in flag's "absent means unchanged" posture.
     */
    @SuppressWarnings("unchecked")
    public static Set<String> enabledTools(Map<String, Object> providerOptions) {
        if (providerOptions == null) {
            return Set.of();
        }
        Object value = providerOptions.get(OPTION_TOOLS);
        if (value instanceof Boolean enabled) {
            return enabled ? DEFAULT_SERVER_TOOLS : Set.of();
        }
        if (value instanceof java.util.Collection<?> tools) {
            Set<String> enabled = new LinkedHashSet<>();
            for (Object tool : tools) {
                if (tool instanceof String name && !name.isBlank()) {
                    enabled.add(name.trim());
                }
            }
            return enabled;
        }
        return Set.of();
    }

    /**
     * The single mutation both transports share: append {@code {"type":<tool>}} entries to the
     * request's {@code tools} array (created if absent), skipping any {@code type} already present
     * so a request that legitimately carries one is never duplicated. A body that is not a JSON
     * object - or unparseable for any reason - is returned byte-identical: this hook must never be
     * the reason a chat request fails.
     */
    static byte[] inject(byte[] body, Set<String> serverTools) {
        if (serverTools == null || serverTools.isEmpty()) {
            return body;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!(root instanceof ObjectNode request)) {
                return body;
            }
            JsonNode existing = request.get("tools");
            ArrayNode tools;
            if (existing == null || existing.isNull()) {
                tools = MAPPER.createArrayNode();
                request.set("tools", tools);
            } else if (existing instanceof ArrayNode array) {
                tools = array;
            } else {
                return body;
            }
            Set<String> present = new LinkedHashSet<>();
            for (JsonNode tool : tools) {
                present.add(tool.path("type").asString(""));
            }
            for (String type : serverTools) {
                if (present.add(type)) {
                    tools.addObject().put("type", type);
                }
            }
            return MAPPER.writeValueAsBytes(request);
        } catch (Exception e) {
            return body;
        }
    }
}
