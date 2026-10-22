package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The A2A JSON-RPC 2.0 binding.
 *
 * <ul>
 *   <li>{@code POST /api/a2a} - the platform endpoint the well-known card advertises; the target
 *       agent comes from {@code params.metadata.agentId} (or {@code
 *       params.message.metadata.agentId}).
 *   <li>{@code POST /api/agents/{agentId}/a2a} - the agent-scoped endpoint the per-agent card
 *       advertises; the route fixes the agent.
 * </ul>
 *
 * Both fall through to {@code SecurityConfig}'s {@code anyRequest().authenticated()} (a Keycloak
 * bearer JWT - exactly what both cards' {@code securitySchemes} advertise) and enforce RUNNER
 * access on the target agent per call, the same gate as {@code AgentController#chat}.
 *
 * <p><b>Methods</b> - each accepted under BOTH its 1.0 and its 0.3 name regardless of the {@code
 * A2A-Version} header (see {@link A2aVersion}): {@code SendMessage}/{@code message/send}, {@code
 * SendStreamingMessage}/ {@code message/stream}, {@code GetTask}/{@code tasks/get}, {@code
 * CancelTask}/{@code tasks/cancel}. The header decides the RESPONSE dialect only.
 *
 * <p><b>Why this writes to {@link HttpServletResponse} directly</b> instead of returning a {@code
 * ResponseEntity} or a {@code Flux<ServerSentEvent>} like {@code VoiceCallController}: one handler
 * must answer either a JSON document or an SSE stream depending on the METHOD IN THE BODY, and
 * Spring MVC picks its return-value handler from the declared return type, so no single declaration
 * covers both. Writing the stream synchronously on the request thread also keeps {@code
 * TenantContext} (a ThreadLocal the servlet filter clears when the request ends) valid for the
 * whole turn - the same block-on-the-request-thread trade {@code McpCallAgentToolCallback} makes,
 * and documents, for the MCP surface.
 *
 * <p>Every failure - including a body that isn't JSON - is an HTTP 200 with a JSON-RPC {@code
 * error} member, per the binding; see {@link A2aError}.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@RestController
@ConditionalOnProperty(
        prefix = "app.a2a",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class A2aJsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);

    static final String PLATFORM_ROUTE = "/api/a2a";
    static final String AGENT_ROUTE = "/api/agents/{agentId}/a2a";

    /** Sentinel pushed onto the stream drain queue when the task reaches a terminal state. */
    private static final Object STREAM_DONE = new Object();

    private final A2aTaskService taskService;
    private final AgentService agentService;
    private final ObjectMapper mapper;

    public A2aJsonRpcController(
            A2aTaskService taskService, AgentService agentService, ObjectMapper mapper) {
        this.taskService = taskService;
        this.agentService = agentService;
        this.mapper = mapper;
    }

    @PostMapping(value = PLATFORM_ROUTE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public void platform(
            @RequestBody String body,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        handle(null, body, versionHeader, principal, response);
    }

    @PostMapping(value = AGENT_ROUTE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public void agentScoped(
            @PathVariable UUID agentId,
            @RequestBody String body,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        handle(agentId, body, versionHeader, principal, response);
    }

    /**
     * Package-private entry both routes share, and what the unit tests drive directly with a {@code
     * MockHttpServletResponse}.
     *
     * @param routeAgentId the agent fixed by the URL, or null on the platform route.
     */
    void handle(
            UUID routeAgentId,
            String body,
            String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        A2aVersion version;
        try {
            version = A2aVersion.fromHeader(versionHeader);
        } catch (A2aError e) {
            writeJson(response, A2aVersion.V0_3, envelope(NullNode.getInstance(), null, e));
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            writeJson(
                    response,
                    version,
                    envelope(
                            NullNode.getInstance(),
                            null,
                            A2aError.parseError(e.getOriginalMessage())));
            return;
        }
        JsonNode id =
                request != null && request.isObject() && request.has("id")
                        ? request.get("id")
                        : NullNode.getInstance();
        try {
            if (request == null || request.isMissingNode() || request.isNull()) {
                throw A2aError.invalidRequest("empty body");
            }
            if (request.isArray()) {
                throw A2aError.invalidRequest("batch requests are not supported");
            }
            if (!request.isObject()) {
                throw A2aError.invalidRequest("body must be a JSON object");
            }
            if (!"2.0".equals(request.path("jsonrpc").asText(null))) {
                throw A2aError.invalidRequest("jsonrpc must be \"2.0\"");
            }
            String method = request.path("method").asText(null);
            if (!StringUtils.hasText(method)) {
                throw A2aError.invalidRequest("method is required");
            }
            JsonNode params = request.path("params");
            switch (method) {
                case "SendMessage", "message/send" ->
                        writeJson(
                                response,
                                version,
                                envelope(
                                        id,
                                        sendMessage(routeAgentId, params, version, principal),
                                        null));
                case "SendStreamingMessage", "message/stream" ->
                        streamMessage(routeAgentId, params, version, principal, id, response);
                case "GetTask", "tasks/get" ->
                        writeJson(
                                response,
                                version,
                                envelope(id, getTask(params, version, principal), null));
                case "CancelTask", "tasks/cancel" ->
                        writeJson(
                                response,
                                version,
                                envelope(id, cancelTask(params, version, principal), null));
                // Spec operations this server does not offer (the card advertises
                // pushNotifications/extendedAgentCard false): the spec's own errors, not a
                // generic -32601, so a client can tell "not offered" from "typo".
                case "ListTasks", "SubscribeToTask", "GetExtendedAgentCard" ->
                        throw A2aError.unsupportedOperation(method);
                case "CreateTaskPushNotificationConfig",
                        "GetTaskPushNotificationConfig",
                        "ListTaskPushNotificationConfigs",
                        "DeleteTaskPushNotificationConfig",
                        "tasks/pushNotificationConfig/set",
                        "tasks/pushNotificationConfig/get",
                        "tasks/pushNotificationConfig/list",
                        "tasks/pushNotificationConfig/delete" ->
                        throw A2aError.pushNotificationsNotSupported();
                default -> throw A2aError.methodNotFound(method);
            }
        } catch (A2aError e) {
            writeJson(response, version, envelope(id, null, e));
        } catch (AgentNotFoundException | ResourceAccessDeniedException e) {
            // One message for "no such agent" and "not yours": confirming which
            // would let a caller enumerate other tenants' agent ids - same 404-
            // not-403 posture GlobalExceptionHandler applies to /api/agents.
            writeJson(
                    response,
                    version,
                    envelope(id, null, A2aError.invalidParams("unknown agentId")));
        } catch (RuntimeException e) {
            log.warn("A2A request failed unexpectedly", e);
            writeJson(response, version, envelope(id, null, A2aError.internal("request failed")));
        }
    }

    // ------------------------------------------------------------- methods

    private Object sendMessage(
            UUID routeAgentId, JsonNode params, A2aVersion version, Principal principal) {
        Inbound inbound = parseInbound(routeAgentId, params);
        A2aTask task =
                taskService.create(
                        inbound.agentId,
                        inbound.contextId,
                        inbound.messageId,
                        inbound.text,
                        principal);
        if (inbound.blocking) {
            taskService.run(task, null);
            taskService.awaitBlocking(task);
        } else {
            taskService.runAsync(task);
        }
        return A2aWire.sendMessageResult(
                A2aWire.task(task, version, inbound.historyLength), version);
    }

    private Object getTask(JsonNode params, A2aVersion version, Principal principal) {
        String taskId = requireTaskId(params);
        Integer historyLength =
                params.hasNonNull("historyLength") ? params.get("historyLength").asInt() : null;
        return A2aWire.task(taskService.get(taskId, principal), version, historyLength);
    }

    private Object cancelTask(JsonNode params, A2aVersion version, Principal principal) {
        return A2aWire.task(taskService.cancel(requireTaskId(params), principal), version, null);
    }

    /**
     * SSE, written synchronously in the exact order 1.0 enforces: the SUBMITTED Task, a WORKING
     * status update, one artifact update per token (first {@code append:false}, then {@code
     * append:true}), a final empty {@code lastChunk:true} artifact update on success, and a
     * terminal status update with {@code final:true}. Every frame is {@code data:} only (no event
     * name), which is what both dialects' clients parse.
     *
     * @param id the JSON-RPC request id each frame is enveloped with; {@code null} writes bare
     *     stream results, which is what the REST binding's SSE frames are ({@link
     *     A2aRestController}).
     */
    void streamMessage(
            UUID routeAgentId,
            JsonNode params,
            A2aVersion version,
            Principal principal,
            JsonNode id,
            HttpServletResponse response)
            throws IOException {
        Inbound inbound = parseInbound(routeAgentId, params);
        A2aTask task =
                taskService.create(
                        inbound.agentId,
                        inbound.contextId,
                        inbound.messageId,
                        inbound.text,
                        principal);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // proxies must not buffer a live stream
        response.setHeader(A2aVersion.HEADER, version.wire());
        PrintWriter out = response.getWriter();

        // Deltas cross from the reactor thread to THIS thread through the queue,
        // so the servlet writer is only ever touched from here.
        LinkedBlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        try {
            writeFrame(
                    out,
                    id,
                    A2aWire.streamResult("task", A2aWire.task(task, version, null), version));
            taskService
                    .run(task, frames::add)
                    .whenComplete((ignored, err) -> frames.add(STREAM_DONE));
            A2aTask.Snapshot afterStart = task.snapshot();
            // A turn that failed synchronously inside run() (kill switch, budget)
            // is already terminal - skip the WORKING frame so the client sees
            // one terminal status, not a non-final FAILED followed by a final
            // one. Otherwise WORKING is rendered EXPLICITLY (not from the live
            // snapshot): a Flux that completed synchronously has the task at
            // COMPLETED already, and the spec order still requires WORKING here.
            if (afterStart.state() != A2aTaskState.FAILED) {
                A2aTask.Snapshot working =
                        new A2aTask.Snapshot(
                                A2aTaskState.WORKING, afterStart.timestamp(), "", null);
                writeFrame(
                        out,
                        id,
                        A2aWire.streamResult(
                                "statusUpdate",
                                A2aWire.statusUpdate(task, working, version, false),
                                version));
            }

            long timeoutMillis = taskService.blockingTimeout().toMillis();
            boolean first = true;
            while (true) {
                Object frame = frames.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    task.abort(
                            "The agent did not produce output within "
                                    + (timeoutMillis / 1000)
                                    + " seconds.");
                    break;
                }
                if (frame == STREAM_DONE) {
                    break;
                }
                writeFrame(
                        out,
                        id,
                        A2aWire.streamResult(
                                "artifactUpdate",
                                A2aWire.artifactUpdate(
                                        task, (String) frame, !first, false, version),
                                version));
                first = false;
            }
            if (task.snapshot().state() == A2aTaskState.COMPLETED) {
                writeFrame(
                        out,
                        id,
                        A2aWire.streamResult(
                                "artifactUpdate",
                                A2aWire.artifactUpdate(task, "", true, true, version),
                                version));
            }
            writeFrame(
                    out,
                    id,
                    A2aWire.streamResult(
                            "statusUpdate",
                            A2aWire.statusUpdate(task, task.snapshot(), version, true),
                            version));
        } catch (IOException clientGone) {
            // The peer hung up mid-stream; stop the LLM call rather than let it
            // finish for nobody. Nothing more can be written.
            log.info("A2A stream for task {} closed by client before completion", task.id());
            task.abort("Client disconnected.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.abort("The request was interrupted before the agent finished.");
        } finally {
            out.flush();
        }
    }

    // ------------------------------------------------------------- parsing

    record Inbound(
            UUID agentId,
            String contextId,
            String messageId,
            String text,
            boolean blocking,
            Integer historyLength) {}

    Inbound parseInbound(UUID routeAgentId, JsonNode params) {
        if (params == null || !params.isObject()) {
            throw A2aError.invalidParams("params must be an object");
        }
        JsonNode message = params.get("message");
        if (message == null || !message.isObject()) {
            throw A2aError.invalidParams("params.message is required");
        }
        if (message.hasNonNull("taskId")) {
            // Multi-turn continuation of ONE task (input-required flows) is not a
            // state this server ever enters - see A2aTaskState. The conversation
            // continues via contextId, so say exactly that.
            throw A2aError.invalidParams(
                    "message.taskId is not supported: every message starts a new task; reuse contextId to continue the conversation");
        }
        String text = A2aWire.extractText(message);
        UUID agentId = resolveAgentId(routeAgentId, params, message);
        // RUNNER on the target agent - the same check AgentController#chat makes
        // at ITS entry point rather than inside AgentChatService#chat (which the
        // omnichannel webhooks also call with synthetic subjects).
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);

        String contextId = message.path("contextId").asText(null);
        if (!StringUtils.hasText(contextId)) {
            contextId = UUID.randomUUID().toString();
        }
        String messageId = message.path("messageId").asText(null);
        if (!StringUtils.hasText(messageId)) {
            messageId = UUID.randomUUID().toString();
        }
        JsonNode configuration = params.path("configuration");
        // Default is BLOCKING. Both dialects' opt-outs are honored: 1.0's
        // returnImmediately:true and 0.3's blocking:false.
        boolean blocking =
                !(configuration.path("returnImmediately").asBoolean(false)
                        || (configuration.has("blocking")
                                && !configuration.path("blocking").asBoolean(true)));
        Integer historyLength =
                configuration.hasNonNull("historyLength")
                        ? configuration.get("historyLength").asInt()
                        : null;
        return new Inbound(agentId, contextId, messageId, text, blocking, historyLength);
    }

    private static UUID resolveAgentId(UUID routeAgentId, JsonNode params, JsonNode message) {
        String fromMetadata = params.path("metadata").path("agentId").asText(null);
        if (!StringUtils.hasText(fromMetadata)) {
            fromMetadata = message.path("metadata").path("agentId").asText(null);
        }
        UUID metadataAgentId = null;
        if (StringUtils.hasText(fromMetadata)) {
            try {
                metadataAgentId = UUID.fromString(fromMetadata);
            } catch (IllegalArgumentException e) {
                throw A2aError.invalidParams("metadata.agentId is not a valid agent id");
            }
        }
        if (routeAgentId != null) {
            if (metadataAgentId != null && !metadataAgentId.equals(routeAgentId)) {
                throw A2aError.invalidParams(
                        "metadata.agentId does not match the agent in the URL");
            }
            return routeAgentId;
        }
        if (metadataAgentId == null) {
            throw A2aError.invalidParams(
                    "metadata.agentId is required on "
                            + PLATFORM_ROUTE
                            + " (or use the agent-scoped endpoint from the agent's card)");
        }
        return metadataAgentId;
    }

    private static String requireTaskId(JsonNode params) {
        String taskId = params == null ? null : params.path("id").asText(null);
        if (!StringUtils.hasText(taskId)) {
            throw A2aError.invalidParams("params.id (task id) is required");
        }
        return taskId;
    }

    // ------------------------------------------------------------- writing

    private Map<String, Object> envelope(JsonNode id, Object result, A2aError error) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id == null ? NullNode.getInstance() : id);
        if (error != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", error.getCode());
            err.put("message", error.getMessage());
            if (error.getData() != null) {
                err.put("data", error.getData());
            }
            envelope.put("error", err);
        } else {
            envelope.put("result", result);
        }
        return envelope;
    }

    private void writeJson(
            HttpServletResponse response, A2aVersion version, Map<String, Object> envelope)
            throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(A2aVersion.HEADER, version.wire());
        response.getWriter().write(mapper.writeValueAsString(envelope));
        response.getWriter().flush();
    }

    private void writeFrame(PrintWriter out, JsonNode id, Object result) throws IOException {
        Object payload = id == null ? result : envelope(id, result, null);
        out.write("data: " + mapper.writeValueAsString(payload) + "\n\n");
        out.flush();
        if (out.checkError()) {
            // PrintWriter swallows IOExceptions; checkError() is the only signal
            // that the client is gone. Surface it as the IOException the stream
            // loop already handles.
            throw new IOException("client connection closed");
        }
    }
}
