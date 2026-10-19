package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestResult;
import com.agenticai.core.agents.tools.web.HttpRequestSpec;
import com.agenticai.core.agents.tools.web.WebPageFetchException;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.dto.SidecarCaptionsResponse;
import com.agenticai.core.knowledge.dto.SidecarRenderJobResponse;
import com.agenticai.core.knowledge.dto.SidecarTtsResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * "HTTP Request" and "Media" node execution, extracted out of {@link WorkflowNodeExecutor}
 * (behaviour-preserving move; method bodies are unchanged). Owned and constructed by {@link
 * WorkflowNodeExecutor}, which keeps the public dispatch API.
 */
class WorkflowHttpMediaNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHttpMediaNodeHandler.class);
    // Media node, render polling. The ceiling is a hard cap on what a
    // workflow author can ask a worker thread to sit through, regardless of
    // the waitSeconds they configure - a node that blocks for an hour is a
    // leaked worker, and the job survives a timeout anyway (its id comes back
    // in the output for a later render_status node).
    private static final Duration RENDER_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_RENDER_WAIT_SECONDS = 900;

    private final ObjectProvider<HttpRequestExecutor> httpRequestExecutor;
    private final ObjectProvider<SidecarClient> sidecarClient;
    private final ObjectMapper objectMapper;

    WorkflowHttpMediaNodeHandler(
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            ObjectProvider<SidecarClient> sidecarClient,
            ObjectMapper objectMapper) {
        this.httpRequestExecutor = httpRequestExecutor;
        this.sidecarClient = sidecarClient;
        this.objectMapper = objectMapper;
    }

    private String writeJsonQuietly(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * "HTTP Request" node - one outbound call to any public REST API or webhook, the n8n/Zapier
     * connector-catalog staple. Config:
     *
     * <ul>
     *   <li>{@code method} (GET default), {@code url} (required) - the URL, every header value,
     *       every query value and the body are resolved by {@link WorkflowExpressionResolver}
     *       before this runs ({@code {{input}}}, {@code {{field}}}, full expressions, plus {@code
     *       {{input_json}}} = the input as a JSON string literal, for embedding free text inside a
     *       JSON body without breaking it).
     *   <li>{@code headers} / {@code query} - a list of {@code {name, value}} pairs (the canvas
     *       form's shape), or a plain object; either may be a raw JSON string. A value may
     *       reference a vault secret as {@code {{secret.NAME}}} - resolved and redacted INSIDE
     *       {@link HttpRequestExecutor}, never here, never logged.
     *   <li>{@code bodyType} none|json|text|form and {@code body}.
     *   <li>{@code timeoutSeconds} (max 30), {@code failOnHttpError} (default true: a non-2xx marks
     *       this node SKIPPED with the status in its summary so a downstream Condition can branch
     *       on it; false lets the response flow out regardless).
     *   <li>{@code saveAs} - blank: this node's output IS the response body (parsed JSON
     *       re-serialised compactly, else the raw text) - the shape a downstream Transform/Agent
     *       node most often wants. Set: the output is a JSON object carrying the input's own
     *       top-level fields (when the input is a JSON object) plus {@code saveAs -> {status, ok,
     *       url, contentType, headers, body, truncated}}, so later nodes can address both the
     *       original input and the full response by name.
     * </ul>
     *
     * Failures follow the Tool node's contract: SKIPPED with the reason, input passed through
     * unchanged, never a thrown exception. Log lines record the method, URL and header NAMES -
     * values are never written anywhere.
     */
    NodeExecutionResult executeHttpNode(WorkflowNodeDto node, String input) {
        HttpRequestExecutor executor = httpRequestExecutor.getIfAvailable();
        if (executor == null) {
            return NodeExecutionResult.skipped(
                    input, "HTTP requests are not available in this deployment.");
        }
        String urlTemplate = stringConfig(node, "url", null);
        if (urlTemplate == null) {
            return NodeExecutionResult.skipped(
                    input, "No URL configured for this HTTP Request node.");
        }
        String method =
                stringConfig(node, "method", "GET").strip().toUpperCase(java.util.Locale.ROOT);
        // url, header/query values and body arrive already resolved (ExpressionFields: http) - this
        // handler
        // substitutes nothing itself, so a value that happens to contain "{{...}}" is never scanned
        // again.
        String url = urlTemplate;
        Map<String, String> headers =
                new java.util.LinkedHashMap<>(pairsConfig(node.config().get("headers")));
        Map<String, String> query =
                new java.util.LinkedHashMap<>(pairsConfig(node.config().get("query")));
        String bodyType =
                stringConfig(node, "bodyType", "none").strip().toLowerCase(java.util.Locale.ROOT);
        String body = "none".equals(bodyType) ? null : stringConfig(node, "body", "");
        Integer timeoutSeconds = intConfigOrNull(node, "timeoutSeconds");
        Object failRaw = node.config().get("failOnHttpError");
        boolean failOnHttpError = failRaw == null || !"false".equalsIgnoreCase(failRaw.toString());
        String saveAs = stringConfig(node, "saveAs", null);

        List<String> logLines = new ArrayList<>();
        logLines.add(
                method
                        + " "
                        + truncate(url, 300)
                        + (headers.isEmpty()
                                ? ""
                                : " | headers: " + String.join(", ", headers.keySet()))
                        + (body == null
                                ? ""
                                : " | body: " + bodyType + ", " + safeLength(body) + " chars"));
        try {
            String userId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
            HttpRequestResult result =
                    executor.execute(
                            new HttpRequestSpec(
                                    method,
                                    url,
                                    headers,
                                    query,
                                    body,
                                    "none".equals(bodyType) ? null : bodyType,
                                    timeoutSeconds,
                                    null),
                            userId);
            logLines.add(
                    "HTTP "
                            + result.status()
                            + " ("
                            + safeLength(result.body())
                            + " chars"
                            + (result.bodyTruncated() ? ", truncated" : "")
                            + ")");
            if (!result.ok() && failOnHttpError) {
                return NodeExecutionResult.skipped(
                        input,
                        "HTTP "
                                + result.status()
                                + " from "
                                + method
                                + " "
                                + truncate(url, 120)
                                + ": "
                                + truncate(result.body(), 200),
                        logLines);
            }
            String output = httpNodeOutput(result, input, saveAs);
            return NodeExecutionResult.ok(
                    output,
                    "HTTP " + result.status() + " " + method + " " + truncate(url, 120),
                    null,
                    logLines);
        } catch (WebPageFetchException e) {
            // Person-readable, already secret-redacted reason from the executor.
            logLines.add("Request failed: " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input, "HTTP request failed: " + e.getMessage(), logLines);
        } catch (RuntimeException e) {
            // Deliberately NOT quoting e.getMessage(): anything below the
            // executor's own redaction boundary could in principle carry a
            // resolved header value, and a run log is a transcript.
            log.warn("HTTP Request node {} failed unexpectedly", node.id(), e);
            logLines.add("Request failed unexpectedly.");
            return NodeExecutionResult.skipped(
                    input, "HTTP request failed unexpectedly - see server logs.", logLines);
        }
    }

    /**
     * {@code renderTemplate}'s value set plus {@code input_json} - see {@link #executeHttpNode}.
     */
    /**
     * "Media" node - narration, captions or a video render, via the sidecar.
     *
     * <p>Config is one field, {@code operation}, which is {@code tts} | {@code captions} | {@code
     * render} | {@code render_status}. There is deliberately no URL and no endpoint in the config:
     * the tenant picks an operation, and core decides what to call. See {@link
     * WorkflowNodeType#MEDIA} for why that is the only sound shape here.
     *
     * <p>The node's INPUT is the request body, as JSON - whatever the chosen sidecar endpoint
     * accepts. It is passed through unvalidated on purpose: the sidecar's own Pydantic models
     * reject a malformed body with a 422 naming the exact offending field, which is a better error
     * than anything this method could synthesise, and it means the render request can grow a field
     * without a Java change.
     *
     * <p><b>{@code render} is the interesting one.</b> A render is minutes of single-core x264,
     * which outlives this node's step timeout and every proxy in front of it, so the sidecar
     * answers 202 with a job id. This node therefore has two modes:
     *
     * <ul>
     *   <li>{@code render} with {@code waitSeconds} at 0 (the default) submits and returns the job
     *       id immediately. Pair it with a later {@code render_status} node.
     *   <li>{@code render} with {@code waitSeconds} &gt; 0 polls until the job is terminal. Simpler
     *       to author, but the workflow's own step timeout has to be raised to match ({@code PATCH
     *       /api/workflows/{id}/execution-limits}), and it holds a worker thread for the duration -
     *       which is why it is not the default.
     * </ul>
     *
     * <p>Failures follow the Tool and HTTP nodes' contract: never a thrown exception, always
     * SKIPPED with the reason and the input passed through unchanged, so a downstream Condition can
     * branch on it.
     */
    NodeExecutionResult executeMediaNode(WorkflowNodeDto node, String input) {
        SidecarClient client = sidecarClient.getIfAvailable();
        if (client == null) {
            // The "knowledge" profile is not active, so there is no sidecar
            // bean. Skipped rather than failed: this is a deployment shape, not
            // a workflow error.
            return NodeExecutionResult.skipped(
                    input,
                    "Media operations are not available in this deployment (the sidecar client "
                            + "requires the 'knowledge' profile).");
        }
        String operation = stringConfig(node, "operation", null);
        if (operation == null) {
            return NodeExecutionResult.skipped(
                    input,
                    "No operation selected for this Media node (expected tts, captions, render "
                            + "or render_status).");
        }
        operation = operation.strip().toLowerCase(java.util.Locale.ROOT);

        List<String> logLines = new ArrayList<>();
        try {
            switch (operation) {
                case "tts" -> {
                    Map<String, Object> request = readRequestBody(input, "tts");
                    SidecarTtsResponse response = client.tts(request);
                    // The measured total is the whole point of the endpoint, so
                    // it goes in the trace where a human reviewing a run can
                    // see it against the brief's estimate.
                    logLines.add(
                            String.format(
                                    java.util.Locale.ROOT,
                                    "Synthesized %d segment(s), %.2fs total at %dHz (%s / %s)",
                                    response.segments() == null ? 0 : response.segments().size(),
                                    response.totalDurationSec(),
                                    response.sampleRate(),
                                    response.language(),
                                    response.model()));
                    return NodeExecutionResult.ok(
                            writeJsonQuietly(response),
                            String.format(
                                    java.util.Locale.ROOT,
                                    "Narration: %.2fs measured",
                                    response.totalDurationSec()),
                            null,
                            logLines);
                }
                case "captions" -> {
                    Map<String, Object> request = readRequestBody(input, "captions");
                    SidecarCaptionsResponse response = client.captions(request);
                    logLines.add(
                            response.wordCount() + " words -> " + response.cueCount() + " cues");
                    return NodeExecutionResult.ok(
                            writeJsonQuietly(response),
                            "Captions: " + response.cueCount() + " cues",
                            null,
                            logLines);
                }
                case "render" -> {
                    Map<String, Object> request = readRequestBody(input, "render");
                    SidecarRenderJobResponse job = client.renderSubmit(request);
                    logLines.add(
                            "Render job "
                                    + job.jobId()
                                    + " queued (queue depth "
                                    + job.queueDepth()
                                    + ")");
                    Integer waitSeconds = intConfigOrNull(node, "waitSeconds");
                    if (waitSeconds == null || waitSeconds <= 0) {
                        return NodeExecutionResult.ok(
                                writeJsonQuietly(job),
                                "Render queued: " + job.jobId(),
                                null,
                                logLines);
                    }
                    return awaitRender(client, job, waitSeconds, input, logLines);
                }
                case "render_status" -> {
                    String jobId = stringConfig(node, "jobId", null);
                    if (jobId == null) {
                        jobId = readJobIdFrom(input);
                    }
                    if (jobId == null) {
                        return NodeExecutionResult.skipped(
                                input,
                                "No jobId configured on this Media node and none found in its input "
                                        + "(expected a job_id or jobId field).");
                    }
                    SidecarRenderJobResponse job = client.renderStatus(jobId);
                    logLines.add(
                            "Render "
                                    + jobId
                                    + " is "
                                    + job.status()
                                    + (job.detail() == null ? "" : " - " + job.detail()));
                    if (job.isFailed()) {
                        return NodeExecutionResult.skipped(
                                writeJsonQuietly(job),
                                "Render " + jobId + " failed: " + truncate(job.error(), 300),
                                logLines);
                    }
                    return NodeExecutionResult.ok(
                            writeJsonQuietly(job),
                            "Render " + jobId + ": " + job.status(),
                            null,
                            logLines);
                }
                default -> {
                    return NodeExecutionResult.skipped(
                            input,
                            "Media node has an unrecognized operation '"
                                    + operation
                                    + "' (expected tts, captions, render or render_status).");
                }
            }
        } catch (RuntimeException e) {
            // SidecarClient throws InvalidKnowledgeUploadException for a 4xx
            // (whose message is the sidecar's own "detail", already specific)
            // and SidecarCommunicationException otherwise. Both are safe to
            // show: neither carries a credential, because the only secret in
            // play is the X-Internal-Token this process sets itself and never
            // echoes.
            log.warn("Media node {} operation {} failed", node.id(), operation, e);
            logLines.add("Failed: " + e.getMessage());
            return NodeExecutionResult.skipped(
                    input,
                    "Media operation '" + operation + "' failed: " + truncate(e.getMessage(), 300),
                    logLines);
        }
    }

    /**
     * Poll a render to a terminal state.
     *
     * <p>A fixed five-second interval rather than a backoff: a render's progress is reported in its
     * {@code detail} field and a workflow author watching a run wants that to move, and the poll
     * costs one cheap in-network GET. Timing out is NOT treated as a failure - the job is still
     * running on the sidecar, so the output carries the job id and a later {@code render_status}
     * node can pick it up rather than the work being lost.
     */
    private NodeExecutionResult awaitRender(
            SidecarClient client,
            SidecarRenderJobResponse submitted,
            int waitSeconds,
            String input,
            List<String> logLines) {
        long deadline =
                System.currentTimeMillis() + Math.min(waitSeconds, MAX_RENDER_WAIT_SECONDS) * 1000L;
        SidecarRenderJobResponse job = submitted;
        String lastDetail = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(RENDER_POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                logLines.add("Interrupted while waiting for render " + job.jobId());
                return NodeExecutionResult.ok(
                        writeJsonQuietly(job),
                        "Render " + job.jobId() + " still running (interrupted)",
                        null,
                        logLines);
            }
            job = client.renderStatus(job.jobId());
            if (job.detail() != null && !job.detail().equals(lastDetail)) {
                logLines.add(job.status() + ": " + job.detail());
                lastDetail = job.detail();
            }
            if (job.isFailed()) {
                return NodeExecutionResult.skipped(
                        writeJsonQuietly(job),
                        "Render " + job.jobId() + " failed: " + truncate(job.error(), 300),
                        logLines);
            }
            if (job.isCompleted()) {
                Object duration = job.result() == null ? null : job.result().get("duration_sec");
                return NodeExecutionResult.ok(
                        writeJsonQuietly(job),
                        "Render "
                                + job.jobId()
                                + " completed"
                                + (duration == null ? "" : " (" + duration + "s)"),
                        null,
                        logLines);
            }
        }
        logLines.add("Still running after " + waitSeconds + "s - not cancelled.");
        return NodeExecutionResult.ok(
                writeJsonQuietly(job),
                "Render "
                        + job.jobId()
                        + " still running after "
                        + waitSeconds
                        + "s - poll it with a render_status node",
                null,
                logLines);
    }

    /**
     * The node's input as a JSON object, to be used as a sidecar request body.
     *
     * <p>A non-object input is a workflow-authoring mistake worth naming clearly, because the most
     * likely cause is an upstream node that emits prose where this node needs structure.
     */
    private Map<String, Object> readRequestBody(String input, String operation) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                    "the Media node's input is empty, but /"
                            + operation
                            + " needs a JSON request body");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = objectMapper.readTree(input);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException(
                        "the Media node's input must be a JSON object for /"
                                + operation
                                + ", but it is "
                                + parsed.getNodeType().name().toLowerCase(java.util.Locale.ROOT)
                                + " - check what the upstream node emits");
            }
            return objectMapper.convertValue(
                    parsed, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "the Media node's input is not valid JSON, so it cannot be a /"
                            + operation
                            + " request body: "
                            + e.getOriginalMessage());
        }
    }

    /** A render job id out of this node's input, accepting either spelling. */
    private String readJobIdFrom(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = objectMapper.readTree(input);
            for (String key : new String[] {"job_id", "jobId"}) {
                com.fasterxml.jackson.databind.JsonNode value = parsed.get(key);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
        return null;
    }

    /**
     * Tolerant name/value-pair reader for the HTTP node's headers/query config: a list of {@code
     * {name|key, value}} maps (what NodeConfigDrawer saves), a plain object, or a raw JSON string
     * of either - the same "never trust the shape of a config value" posture {@code resolveRoutes}
     * takes.
     */
    private Map<String, String> pairsConfig(Object raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Object parsed = raw;
        if (raw instanceof String text) {
            if (text.isBlank()) {
                return out;
            }
            try {
                parsed = objectMapper.readValue(text, Object.class);
            } catch (Exception e) {
                return out;
            }
        }
        if (parsed instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().toString().isBlank()) {
                    out.put(
                            entry.getKey().toString().strip(),
                            entry.getValue() == null ? "" : entry.getValue().toString());
                }
            }
        } else if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> pair) {
                    Object name = pair.get("name") != null ? pair.get("name") : pair.get("key");
                    Object value = pair.get("value");
                    if (name != null && !name.toString().isBlank()) {
                        out.put(name.toString().strip(), value == null ? "" : value.toString());
                    }
                }
            }
        }
        return out;
    }

    private Integer intConfigOrNull(WorkflowNodeDto node, String key) {
        String raw = stringConfig(node, key, null);
        if (raw == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String httpNodeOutput(HttpRequestResult result, String input, String saveAs) {
        try {
            if (saveAs == null) {
                JsonNode bodyJson = result.bodyAsJson(objectMapper);
                return bodyJson == null ? result.body() : objectMapper.writeValueAsString(bodyJson);
            }
            com.fasterxml.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
            try {
                JsonNode root =
                        objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
                if (root.isObject()) {
                    out = ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy();
                } else if (input != null && !input.isBlank()) {
                    out.put("input", input);
                }
            } catch (Exception notJson) {
                if (input != null && !input.isBlank()) {
                    out.put("input", input);
                }
            }
            out.set(saveAs.strip(), result.toJson(objectMapper));
            return objectMapper.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return result.body();
        }
    }
}
