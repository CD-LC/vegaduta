package com.agenticai.core.chat;

import com.agenticai.core.providers.ToolCallCapabilityProbe;
import com.agenticai.core.providers.ToolCallGrade;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@link ToolCallCapabilityProbe} the hosted-pool service calls when a row is saved: one canned
 * two-tool chat request, graded by what came back (see {@link ToolCallGrade}).
 *
 * <p>Ollama first: {@code POST /api/show} reports the model's {@code capabilities}; a model without
 * {@code "tools"} is graded {@link ToolCallGrade#NONE} without a model call, and the reply also
 * carries the parameter size. Then {@code /version} (vLLM) and {@code /props} (llama.cpp server)
 * tell the server kind, which the constrained tool router uses to pick its mode. Any failure to
 * detect the kind is {@link ToolCallCapabilityProbe.ServerKind#OTHER}, never an error.
 *
 * <p>The chat call offers {@code get_weather} and {@code get_time} and asks for the weather in
 * Paris, {@code tool_choice: auto}, {@code temperature: 0}. A structured {@code tool_calls} entry
 * is {@link ToolCallGrade#STRUCTURED}; a reply {@link TextToolCallParser} recognises as a call to
 * one of the two tools is {@link ToolCallGrade#TEXT}; prose is {@link ToolCallGrade#NONE}. A 4xx
 * that names the tools parameter (a server that rejects {@code tools}) is also NONE. A transport
 * failure grades nothing (empty), so the caller keeps whatever grade it had.
 */
@Component
public class HostedToolCallGradeProbe implements ToolCallCapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(HostedToolCallGradeProbe.class);

    static final Set<String> PROBE_TOOL_NAMES = Set.of("get_weather", "get_time");
    static final String PROBE_QUESTION = "What is the weather in Paris right now?";

    private static final Pattern PARAMETER_SIZE =
            Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*B", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    /**
     * Same probe over the SSRF-guarded transport (address-pinned, no redirects), for a base URL a
     * TENANT typed (the BYOK local-server probe). Null on the guarded instance itself and when the
     * unit-test seam built this.
     */
    private final HostedToolCallGradeProbe guardedProbe;

    // Two constructors: Spring needs to be told which one is its (the package-private one is the
    // unit-test seam). Without this core failed to boot on stage (2026-10-15, "No default
    // constructor found") while every unit test passed.
    @Autowired
    public HostedToolCallGradeProbe(
            LlmHttpClientFactory httpClientFactory,
            ObjectMapper objectMapper,
            @Value("${app.llm.tool-call-probe.enabled:true}") boolean enabled) {
        this(
                httpClientFactory.restClientBuilder(),
                objectMapper,
                enabled,
                new HostedToolCallGradeProbe(
                        httpClientFactory.guardedRestClientBuilder(), objectMapper, enabled, null));
    }

    HostedToolCallGradeProbe(
            RestClient.Builder restClientBuilder, ObjectMapper objectMapper, boolean enabled) {
        this(restClientBuilder, objectMapper, enabled, null);
    }

    private HostedToolCallGradeProbe(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            boolean enabled,
            HostedToolCallGradeProbe guardedProbe) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.guardedProbe = guardedProbe;
    }

    /**
     * The guard (DNS-resolving host check) is the caller's job; the guarded transport here pins the
     * connection to the addresses it validated. Without a guarded instance (unit-test seam) nothing
     * is probed.
     */
    @Override
    public Optional<Result> probeTenantServer(
            String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        return guardedProbe == null
                ? Optional.empty()
                : guardedProbe.probe(baseUrl, completionsPath, model, apiKeyOrNull);
    }

    @Override
    public Optional<Result> probe(
            String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        if (!enabled || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(model)) {
            return Optional.empty();
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            JsonNode show = ollamaShow(base, model);
            ServerKind kind = show != null ? ServerKind.OLLAMA : detectNonOllamaKind(base);
            Double parameterBillions = show == null ? null : parameterBillions(show);
            if (show != null && !ollamaReportsTools(show)) {
                log.info(
                        "Ollama reports no 'tools' capability for model '{}' at {}: graded NONE without a call.",
                        model,
                        base);
                return Optional.of(new Result(ToolCallGrade.NONE, kind, parameterBillions));
            }
            String url =
                    base
                            + (StringUtils.hasText(completionsPath)
                                    ? completionsPath
                                    : "/v1/chat/completions");
            ToolCallGrade grade = gradeChatReply(url, model, apiKeyOrNull);
            if (grade == null) {
                return Optional.empty();
            }
            if (kind == ServerKind.OLLAMA
                    && grade != ToolCallGrade.STRUCTURED
                    && !ollamaHonoursFormat(url, model, apiKeyOrNull)) {
                // Only asked when the router would lean on it: a STRUCTURED row never uses the
                // schema.
                kind = ServerKind.OLLAMA_WITHOUT_FORMAT;
            }
            return Optional.of(new Result(grade, kind, parameterBillions));
        } catch (RuntimeException e) {
            log.warn("Tool-call probe of model '{}' at {} failed: {}", model, base, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean honoursJsonSchema(
            String baseUrl, String completionsPath, String model, String apiKeyOrNull) {
        if (!enabled || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(model)) {
            return true;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url =
                base
                        + (StringUtils.hasText(completionsPath)
                                ? completionsPath
                                : "/v1/chat/completions");
        try {
            return ollamaHonoursFormat(url, model, apiKeyOrNull);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The {@code /api/show} body, or null when this is not an Ollama server (or it did not answer).
     */
    private JsonNode ollamaShow(String base, String model) {
        try {
            ObjectNode body = objectMapper.createObjectNode().put("model", model);
            return restClient
                    .post()
                    .uri(base + "/api/show")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            log.debug("{}/api/show did not answer like Ollama: {}", base, e.toString());
            return null;
        }
    }

    private ServerKind detectNonOllamaKind(String base) {
        if (getsJson(base + "/version", "version")) {
            return ServerKind.VLLM;
        }
        if (getsJson(base + "/props", "default_generation_settings")) {
            return ServerKind.LLAMA_CPP;
        }
        return ServerKind.OTHER;
    }

    private boolean getsJson(String url, String requiredField) {
        try {
            JsonNode body = restClient.get().uri(url).retrieve().body(JsonNode.class);
            return body != null && body.has(requiredField);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean ollamaReportsTools(JsonNode show) {
        JsonNode capabilities = show.get("capabilities");
        if (capabilities == null || !capabilities.isArray()) {
            // An Ollama too old to report capabilities: measure instead of assuming.
            return true;
        }
        for (JsonNode capability : capabilities) {
            if ("tools".equalsIgnoreCase(capability.asString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code details.parameter_size} ("3.2B") first, then {@code
     * model_info.general.parameter_count}.
     */
    static Double parameterBillions(JsonNode show) {
        JsonNode details = show.get("details");
        if (details != null && details.hasNonNull("parameter_size")) {
            Matcher m = PARAMETER_SIZE.matcher(details.get("parameter_size").asString());
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        JsonNode info = show.get("model_info");
        if (info != null && info.hasNonNull("general.parameter_count")) {
            long count = info.get("general.parameter_count").asLong();
            if (count > 0) {
                return count / 1_000_000_000d;
            }
        }
        return null;
    }

    private ToolCallGrade gradeChatReply(String url, String model, String apiKeyOrNull) {
        RestClient.RequestBodySpec request =
                restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKeyOrNull)) {
            request = request.header("Authorization", "Bearer " + apiKeyOrNull.trim());
        }
        JsonNode reply;
        try {
            reply = request.body(probeRequest(model).toString()).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            if (e.getStatusCode().is4xxClientError()
                    && (detail.contains("tool") || detail.contains("function"))) {
                log.info(
                        "{} rejected the tools parameter (HTTP {}): graded NONE.",
                        url,
                        e.getStatusCode().value());
                return ToolCallGrade.NONE;
            }
            throw e;
        }
        return grade(reply, objectMapper);
    }

    /**
     * One tiny request with {@code response_format: json_schema}: false when the server answers a
     * 4xx that names structured output (Ollama 0.19 for a model its MLX runner serves), true
     * otherwise.
     */
    private boolean ollamaHonoursFormat(String url, String model, String apiKeyOrNull) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("max_tokens", 20);
        body.putArray("messages")
                .addObject()
                .put("role", "user")
                .put("content", "Reply with {\"ok\":true}");
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("ok").put("type", "boolean");
        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_schema");
        responseFormat
                .putObject("json_schema")
                .put("name", "probe")
                .put("strict", false)
                .set("schema", schema);
        RestClient.RequestBodySpec request =
                restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKeyOrNull)) {
            request = request.header("Authorization", "Bearer " + apiKeyOrNull.trim());
        }
        try {
            request.body(body.toString()).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            String detail = e.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            boolean refused =
                    e.getStatusCode().is4xxClientError()
                            && (detail.contains("structured output") || detail.contains("format"));
            if (refused) {
                log.info(
                        "{} does not honour response_format for model '{}' ({}): the constrained router will not"
                                + " use it.",
                        url,
                        model,
                        e.getStatusCode().value());
            }
            return !refused;
        } catch (RuntimeException e) {
            log.debug("response_format check at {} failed: {}", url, e.toString());
            return true;
        }
    }

    /**
     * The grade of one non-streaming chat-completions reply; null when it has no message at all.
     */
    static ToolCallGrade grade(JsonNode reply, ObjectMapper objectMapper) {
        if (reply == null) {
            return null;
        }
        JsonNode message = reply.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            return null;
        }
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            return ToolCallGrade.STRUCTURED;
        }
        String content = message.hasNonNull("content") ? message.get("content").asString() : "";
        List<AssistantMessage.ToolCall> textCalls =
                TextToolCallParser.parse(content, PROBE_TOOL_NAMES, objectMapper);
        return textCalls.isEmpty() ? ToolCallGrade.NONE : ToolCallGrade.TEXT;
    }

    ObjectNode probeRequest(String model) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("temperature", 0);
        body.put("max_tokens", 200);
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put(
                        "content",
                        "You can call tools. When a tool answers the question, call it instead of answering yourself.");
        messages.addObject().put("role", "user").put("content", PROBE_QUESTION);
        ArrayNode tools = body.putArray("tools");
        tools.add(
                tool(
                        "get_weather",
                        "Current weather for a city.",
                        "city",
                        "City name, e.g. Paris"));
        tools.add(
                tool(
                        "get_time",
                        "Current time in a time zone.",
                        "timezone",
                        "IANA time zone, e.g. Europe/Paris"));
        body.put("tool_choice", "auto");
        return body;
    }

    private ObjectNode tool(
            String name, String description, String param, String paramDescription) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters
                .putObject("properties")
                .putObject(param)
                .put("type", "string")
                .put("description", paramDescription);
        parameters.putArray("required").add(param);
        return tool;
    }
}
