package com.agenticai.core.chat.structured;

import com.agenticai.core.json.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in OpenAI strict function calling ({@code "strict": true} on a tool definition), behind
 * {@code app.llm.tools.strict.enabled}.
 *
 * <p>Spring AI 1.1.7 builds OpenAI function tools from {@code ToolCallback}s with no way to set
 * {@code strict}, so the flag is applied to the outgoing request body instead: {@link #rewrite}
 * adds {@code strict: true} to exactly those tools whose {@code parameters} satisfy OpenAI's
 * strict-mode subset ({@link #eligible}); every other tool - and every request without tools - is
 * sent byte-for-byte as before. The in-memory tool schemas are never touched; only the serialized
 * copy in flight changes.
 *
 * <p>{@link #restInterceptor()} covers the blocking path, {@link #webFilter()} the streaming one.
 */
public final class ToolStrictMode {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * Keywords outside the strict-mode subset (OpenAI "Supported schemas" docs) plus anything that
     * would make us guess: a schema using any of them is left non-strict.
     */
    static final Set<String> UNSUPPORTED_KEYWORDS =
            Set.of(
                    "patternProperties",
                    "unevaluatedProperties",
                    "unevaluatedItems",
                    "propertyNames",
                    "minProperties",
                    "maxProperties",
                    "contains",
                    "minContains",
                    "maxContains",
                    "uniqueItems",
                    "not",
                    "if",
                    "then",
                    "else",
                    "dependentRequired",
                    "dependentSchemas",
                    "dependencies",
                    "oneOf",
                    "allOf",
                    "prefixItems",
                    "minItems",
                    "maxItems",
                    "minLength",
                    "maxLength",
                    "minimum",
                    "maximum",
                    "exclusiveMinimum",
                    "exclusiveMaximum",
                    "multipleOf",
                    "pattern",
                    "format",
                    "default",
                    "const",
                    "$anchor",
                    "$dynamicRef",
                    "$dynamicAnchor");

    private ToolStrictMode() {}

    /**
     * True when {@code parameters} meets the strict-mode subset: an object root; every object
     * closed ({@code additionalProperties:false}) with all its properties required (reusing {@link
     * StructuredOutput#isStrictCompatible}); and no keyword from {@link #UNSUPPORTED_KEYWORDS}
     * anywhere in a schema position (property NAMES that collide with a keyword are fine). A
     * no-argument tool with an empty {@code properties} object that is already closed qualifies.
     */
    public static boolean eligible(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            return false;
        }
        JsonNode type = parameters.get("type");
        if (type == null || !type.isString() || !"object".equals(type.asString())) {
            return false;
        }
        if (parameters.has("anyOf")) {
            return false; // OpenAI: the root of a strict schema must be an object, not a union
        }
        return JsonSchemaRules.isStrictCompatible(parameters) && noUnsupportedKeywords(parameters);
    }

    private static boolean noUnsupportedKeywords(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return true;
        }
        var names = schema.propertyNames().iterator();
        while (names.hasNext()) {
            if (UNSUPPORTED_KEYWORDS.contains(names.next())) {
                return false;
            }
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            for (JsonNode child : props) {
                if (!noUnsupportedKeywords(child)) {
                    return false;
                }
            }
        }
        for (JsonNode sub : JsonSchemaRules.subSchemas(schema)) {
            if (!noUnsupportedKeywords(sub)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The request body with {@code strict: true} added to every eligible function tool that does
     * not already carry a {@code strict} value; the original array is returned (same bytes) when
     * nothing qualifies, the body is not a JSON object, or it cannot be parsed. {@code $schema} is
     * dropped from a strictified tool's parameters copy.
     */
    public static byte[] rewrite(byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.contains("\"tools\"")) {
            return body;
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            JsonNode tools = root == null ? null : root.get("tools");
            if (!(root instanceof ObjectNode) || tools == null || !tools.isArray()) {
                return body;
            }
            boolean changed = false;
            for (JsonNode tool : tools) {
                JsonNode fn = tool.get("function");
                if (!(fn instanceof ObjectNode fnObj) || fnObj.has("strict")) {
                    continue;
                }
                JsonNode type = tool.get("type");
                if (type != null && type.isString() && !"function".equals(type.asString())) {
                    continue;
                }
                JsonNode parameters = fnObj.get("parameters");
                if (eligible(parameters)) {
                    ObjectNode copy = ((ObjectNode) parameters).deepCopy();
                    copy.remove("$schema");
                    fnObj.set("parameters", copy);
                    fnObj.put("strict", true);
                    changed = true;
                }
            }
            return changed ? MAPPER.writeValueAsBytes(root) : body;
        } catch (JacksonException e) {
            return body;
        }
    }

    // ------------------------------------------------------------- HTTP wiring

    /** Blocking path: rewrites the body before it is sent. */
    public static ClientHttpRequestInterceptor restInterceptor() {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            byte[] out = rewrite(body);
            if (out != body) {
                request.getHeaders().setContentLength(out.length);
            }
            ClientHttpResponse response = execution.execute(request, out);
            return response;
        };
    }

    /** Streaming path: rewrites the (single, JSON) request body before it is sent. */
    public static ExchangeFilterFunction webFilter() {
        return (ClientRequest request, ExchangeFunction next) -> {
            BodyInserter<?, ? super ClientHttpRequest> original = request.body();
            BodyInserter<Object, ClientHttpRequest> wrapped =
                    (outputMessage, context) ->
                            original.insert(new StrictRewritingRequest(outputMessage), context);
            return next.exchange(ClientRequest.from(request).body(wrapped).build());
        };
    }

    private static final class StrictRewritingRequest extends ClientHttpRequestDecorator {

        StrictRewritingRequest(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .flatMap(
                            joined -> {
                                byte[] bytes = new byte[joined.readableByteCount()];
                                joined.read(bytes);
                                DataBufferUtils.release(joined);
                                byte[] out = rewrite(bytes);
                                if (out != bytes) {
                                    getHeaders().setContentLength(out.length);
                                }
                                return super.writeWith(Mono.just(bufferFactory().wrap(out)));
                            })
                    .switchIfEmpty(Mono.defer(() -> super.writeWith(Mono.<DataBuffer>empty())));
        }
    }
}
