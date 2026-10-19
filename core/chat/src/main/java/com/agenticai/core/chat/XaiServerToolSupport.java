package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * xAI Agent Tools API support for the {@link com.agenticai.core.providers.ProviderName#GROK}
 * path (2026-09-23, per {@code docs/plans/INTEGRATION-RESEARCH-2026-09-23.md}).
 *
 * <p>xAI's OpenAI-compatible Chat Completions endpoint accepts SERVER-SIDE tools in the same
 * {@code tools} array as function tools: entries shaped {@code {"type":"web_search"}},
 * {@code {"type":"x_search"}}, {@code {"type":"code_execution"}} (and {@code collections_search}/
 * {@code mcp}, not yet enabled here). xAI executes those on its own infrastructure - web and X
 * search results, remote Python execution - and the model can invoke them across several turns
 * before producing its final answer, exactly like Grok does inside the X app. Unlike this
 * platform's own {@code web_search}/{@code run_code} ToolCallbacks (which run HERE and are
 * governed by guardrails/approvals), these run on xAI's side and are billed by xAI per tool
 * invocation - which is why the whole feature sits behind the per-agent {@code
 * xaiServerToolsEnabled} opt-in (migration V239) rather than being on for every Grok agent.
 *
 * <p><b>Why HTTP-layer injection:</b> Spring AI's {@code OpenAiApi.ChatCompletionRequest#tools}
 * is typed as {@code List<FunctionTool>} - there is no options-level way to express a
 * non-function tool entry in spring-ai 1.1.7. Rather than forking the model/request types, the
 * entries are appended at serialization time: {@link #messageConverter} handles the
 * synchronous RestClient body ({@code .call()}) and {@link #jsonEncoder} the reactive
 * WebClient body ({@code .stream()}), both delegating to {@link #inject} so the two transports
 * can never drift. Anything that is not a {@code ChatCompletionRequest} passes through the
 * default codec untouched.
 */
public final class XaiServerToolSupport {

    /** {@code providerOptions} key on {@code GatewayCallSpec} carrying the enabled tool set. */
    public static final String OPTION_TOOLS = "xaiServerTools";

    /**
     * The standard trio exposed by the per-agent toggle today. {@code collections_search}
     * needs per-tenant collection ids and {@code mcp} a server URL - both deliberately left
     * out until there is a config surface for them (see the research doc).
     */
    public static final Set<String> DEFAULT_SERVER_TOOLS = Set.of("web_search", "x_search", "code_execution");

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private XaiServerToolSupport() {
    }

    /**
     * Reads {@link #OPTION_TOOLS} out of a {@code providerOptions} map. Accepts a
     * {@code Collection<String>} of tool types or the Boolean {@code true} (meaning {@link
     * #DEFAULT_SERVER_TOOLS}); anything else - including absent - resolves to an empty set,
     * i.e. the feature off, matching every other opt-in flag's "absent means unchanged"
     * posture.
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
     * The single mutation both transports share: append {@code {"type":<tool>}} entries to
     * the request's {@code tools} array (created if absent), skipping any {@code type}
     * already present so a request that legitimately carries one is never duplicated. A body
     * that is not a JSON object - or unparseable for any reason - is returned byte-identical:
     * this hook must never be the reason a chat request fails.
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
                present.add(tool.path("type").asText(""));
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

    /**
     * RestClient-side hook for {@link GrokAgentChatClientFactory}: a {@code
     * MappingJackson2HttpMessageConverter} that serializes a {@code ChatCompletionRequest}
     * through the normal Jackson path and then runs {@link #inject} on the resulting bytes.
     * Registered at index 0 of the builder's converter list so it wins for {@code
     * application/json}; every non-chat-request body delegates to the untouched default
     * serialization.
     */
    public static MappingJackson2HttpMessageConverter messageConverter(Set<String> serverTools) {
        return new MappingJackson2HttpMessageConverter() {
            @Override
            protected void writeInternal(Object object, Type type, HttpOutputMessage outputMessage)
                throws IOException, HttpMessageNotWritableException {
                if (!(object instanceof OpenAiApi.ChatCompletionRequest)) {
                    super.writeInternal(object, type, outputMessage);
                    return;
                }
                outputMessage.getBody().write(inject(getObjectMapper().writeValueAsBytes(object), serverTools));
            }
        };
    }

    /**
     * WebClient-side hook for the streaming path. {@code Jackson2JsonEncoder#encode} funnels
     * every element through {@code encodeValue}, so overriding that one method covers both
     * {@code bodyValue(...)} and {@code body(Mono.just(...))} request styles Spring AI uses.
     */
    public static Jackson2JsonEncoder jsonEncoder(Set<String> serverTools) {
        return new Jackson2JsonEncoder(MAPPER, MimeTypeUtils.APPLICATION_JSON) {
            @Override
            public DataBuffer encodeValue(
                Object value, DataBufferFactory bufferFactory, ResolvableType valueType,
                MimeType mimeType, Map<String, Object> hints
            ) {
                if (value instanceof OpenAiApi.ChatCompletionRequest) {
                    try {
                        return bufferFactory.wrap(inject(MAPPER.writeValueAsBytes(value), serverTools));
                    } catch (Exception e) {
                        throw new IllegalStateException("Could not serialize chat request for xAI server tools", e);
                    }
                }
                return super.encodeValue(value, bufferFactory, valueType, mimeType, hints);
            }
        };
    }
}
