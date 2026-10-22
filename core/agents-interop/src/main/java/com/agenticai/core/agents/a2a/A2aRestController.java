package com.agenticai.core.agents.a2a;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The A2A 1.0 HTTP+JSON/REST binding (spec section 11).
 *
 * <p>Two base paths, mirroring the JSON-RPC routes and advertised in the cards' {@code
 * supportedInterfaces} as {@code protocolBinding: "HTTP+JSON"}:
 *
 * <ul>
 *   <li>{@code /api/a2a/rest} - platform base; the agent comes from {@code metadata.agentId}.
 *   <li>{@code /api/agents/{agentId}/a2a/rest} - agent-scoped base.
 * </ul>
 *
 * Operations implemented (the same four the JSON-RPC binding serves, so the two bindings expose
 * identical functionality as section 5.1 requires): {@code POST /message:send}, {@code POST
 * /message:stream} (SSE), {@code GET /tasks/{id}}, {@code POST /tasks/{id}:cancel}. The remaining
 * section 11.3 operations (ListTasks, SubscribeToTask, push-notification configs, extended card)
 * answer with the spec's {@code UnsupportedOperationError} / {@code
 * PushNotificationNotSupportedError} rather than a bare 404, so a client can tell "not offered"
 * from "wrong URL".
 *
 * <p>Errors use the {@code google.rpc.Status} JSON form with a {@code google.rpc.ErrorInfo} detail
 * (section 11.6) and the HTTP statuses of the section 5.4 table - unlike JSON-RPC, where every
 * failure is HTTP 200. The wire dialect is always 1.0: the 0.3 REST binding used different paths,
 * so a request without {@code A2A-Version} is treated as 1.0 here (a documented deviation from the
 * spec's "empty means 0.3" rule, which exists for JSON-RPC clients that predate the header).
 * Business logic is shared with {@link A2aJsonRpcController}: same RUNNER check, same task service,
 * same SSE frame order.
 */
@RestController
@ConditionalOnProperty(
        prefix = "app.a2a",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class A2aRestController {

    private static final Logger log = LoggerFactory.getLogger(A2aRestController.class);

    static final String PLATFORM_BASE = "/api/a2a/rest";
    static final String AGENT_BASE = "/api/agents/{agentId}/a2a/rest";
    static final String MEDIA_TYPE = "application/a2a+json";
    static final String ERROR_DOMAIN = "a2a-protocol.org";

    private final A2aTaskService taskService;
    private final A2aJsonRpcController shared;
    private final ObjectMapper mapper;

    public A2aRestController(
            A2aTaskService taskService, A2aJsonRpcController shared, ObjectMapper mapper) {
        this.taskService = taskService;
        this.shared = shared;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------ operations

    @PostMapping(
            value = {PLATFORM_BASE + "/message:send", AGENT_BASE + "/message:send"},
            consumes = {"application/json", MEDIA_TYPE})
    public void sendMessage(
            @PathVariable(required = false) UUID agentId,
            @RequestBody String body,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    JsonNode request = parseBody(body);
                    A2aJsonRpcController.Inbound inbound = shared.parseInbound(agentId, request);
                    A2aTask task =
                            taskService.create(
                                    inbound.agentId(),
                                    inbound.contextId(),
                                    inbound.messageId(),
                                    inbound.text(),
                                    principal);
                    if (inbound.blocking()) {
                        taskService.run(task, null);
                        taskService.awaitBlocking(task);
                    } else {
                        taskService.runAsync(task);
                    }
                    writeJson(
                            response,
                            HttpStatus.OK,
                            A2aWire.sendMessageResult(
                                    A2aWire.task(task, A2aVersion.V1_0, inbound.historyLength()),
                                    A2aVersion.V1_0));
                },
                versionHeader);
    }

    @PostMapping(
            value = {PLATFORM_BASE + "/message:stream", AGENT_BASE + "/message:stream"},
            consumes = {"application/json", MEDIA_TYPE})
    public void streamMessage(
            @PathVariable(required = false) UUID agentId,
            @RequestBody String body,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    JsonNode request = parseBody(body);
                    // Validates and RUNNER-checks before any byte of the stream is written, so a
                    // bad request is a normal HTTP error, not a half-open event stream.
                    shared.parseInbound(agentId, request);
                    shared.streamMessage(
                            agentId, request, A2aVersion.V1_0, principal, null, response);
                },
                versionHeader);
    }

    @GetMapping(value = {PLATFORM_BASE + "/tasks/{id}", AGENT_BASE + "/tasks/{id}"})
    public void getTask(
            @PathVariable String id,
            @RequestParam(required = false) Integer historyLength,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () ->
                        writeJson(
                                response,
                                HttpStatus.OK,
                                A2aWire.task(
                                        taskService.get(id, principal),
                                        A2aVersion.V1_0,
                                        historyLength)),
                versionHeader);
    }

    @PostMapping(value = {PLATFORM_BASE + "/tasks/{id}:cancel", AGENT_BASE + "/tasks/{id}:cancel"})
    public void cancelTask(
            @PathVariable String id,
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            Principal principal,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () ->
                        writeJson(
                                response,
                                HttpStatus.OK,
                                A2aWire.task(
                                        taskService.cancel(id, principal), A2aVersion.V1_0, null)),
                versionHeader);
    }

    // ------------------------------------------- operations answered as unsupported

    @GetMapping(value = {PLATFORM_BASE + "/tasks", AGENT_BASE + "/tasks"})
    public void listTasks(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    throw A2aError.unsupportedOperation("ListTasks");
                },
                versionHeader);
    }

    @PostMapping(
            value = {PLATFORM_BASE + "/tasks/{id}:subscribe", AGENT_BASE + "/tasks/{id}:subscribe"})
    public void subscribeToTask(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    throw A2aError.unsupportedOperation("SubscribeToTask");
                },
                versionHeader);
    }

    @PostMapping(
            value = {
                PLATFORM_BASE + "/tasks/{id}/pushNotificationConfigs",
                AGENT_BASE + "/tasks/{id}/pushNotificationConfigs"
            })
    public void createPushConfig(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    throw A2aError.pushNotificationsNotSupported();
                },
                versionHeader);
    }

    @GetMapping(
            value = {
                PLATFORM_BASE + "/tasks/{id}/pushNotificationConfigs",
                AGENT_BASE + "/tasks/{id}/pushNotificationConfigs",
                PLATFORM_BASE + "/tasks/{id}/pushNotificationConfigs/{configId}",
                AGENT_BASE + "/tasks/{id}/pushNotificationConfigs/{configId}"
            })
    public void readPushConfig(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    throw A2aError.pushNotificationsNotSupported();
                },
                versionHeader);
    }

    @DeleteMapping(
            value = {
                PLATFORM_BASE + "/tasks/{id}/pushNotificationConfigs/{configId}",
                AGENT_BASE + "/tasks/{id}/pushNotificationConfigs/{configId}"
            })
    public void deletePushConfig(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        run(
                response,
                () -> {
                    throw A2aError.pushNotificationsNotSupported();
                },
                versionHeader);
    }

    @GetMapping(value = {PLATFORM_BASE + "/extendedAgentCard", AGENT_BASE + "/extendedAgentCard"})
    public void extendedAgentCard(
            @RequestHeader(value = A2aVersion.HEADER, required = false) String versionHeader,
            HttpServletResponse response)
            throws IOException {
        // Spec section 13: capabilities.extendedAgentCard is false -> UnsupportedOperationError.
        run(
                response,
                () -> {
                    throw A2aError.unsupportedOperation("GetExtendedAgentCard");
                },
                versionHeader);
    }

    // ----------------------------------------------------------------- plumbing

    /** One operation body; may throw {@link A2aError} or the access exceptions mapped below. */
    @FunctionalInterface
    private interface Operation {
        void execute() throws IOException;
    }

    private void run(HttpServletResponse response, Operation operation, String versionHeader)
            throws IOException {
        try {
            requireRestVersion(versionHeader);
            operation.execute();
        } catch (A2aError e) {
            writeError(response, e);
        } catch (AgentNotFoundException | ResourceAccessDeniedException e) {
            // "No such agent" and "not yours" are indistinguishable, as in the JSON-RPC binding.
            writeStatusError(
                    response,
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "AGENT_NOT_FOUND",
                    "unknown agentId");
        } catch (RuntimeException e) {
            log.warn("A2A REST request failed unexpectedly", e);
            writeStatusError(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL",
                    "INTERNAL",
                    "request failed");
        }
    }

    /** REST is a 1.0-only binding; an absent header is accepted as 1.0 (see class javadoc). */
    static void requireRestVersion(String header) {
        if (!StringUtils.hasText(header)) {
            return;
        }
        if (A2aVersion.fromHeader(header) != A2aVersion.V1_0) {
            throw A2aError.versionNotSupported(header.trim());
        }
    }

    private JsonNode parseBody(String body) {
        JsonNode node;
        try {
            node = mapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            throw A2aError.parseError(e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw A2aError.invalidRequest("body must be a JSON object");
        }
        return node;
    }

    /** Section 5.4 JSON-RPC code -> HTTP status, gRPC status name and ErrorInfo reason. */
    record Mapping(HttpStatus http, String status, String reason) {}

    static Mapping mapping(int code) {
        return switch (code) {
            case A2aError.TASK_NOT_FOUND ->
                    new Mapping(HttpStatus.NOT_FOUND, "NOT_FOUND", "TASK_NOT_FOUND");
            case A2aError.TASK_NOT_CANCELABLE ->
                    new Mapping(
                            HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "TASK_NOT_CANCELABLE");
            case A2aError.PUSH_NOTIFICATION_NOT_SUPPORTED ->
                    new Mapping(
                            HttpStatus.BAD_REQUEST,
                            "FAILED_PRECONDITION",
                            "PUSH_NOTIFICATION_NOT_SUPPORTED");
            case A2aError.UNSUPPORTED_OPERATION ->
                    new Mapping(
                            HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "UNSUPPORTED_OPERATION");
            case A2aError.CONTENT_TYPE_NOT_SUPPORTED ->
                    new Mapping(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_ARGUMENT",
                            "CONTENT_TYPE_NOT_SUPPORTED");
            case A2aError.VERSION_NOT_SUPPORTED ->
                    new Mapping(
                            HttpStatus.BAD_REQUEST, "FAILED_PRECONDITION", "VERSION_NOT_SUPPORTED");
            case A2aError.PARSE_ERROR, A2aError.INVALID_REQUEST, A2aError.INVALID_PARAMS ->
                    new Mapping(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "INVALID_ARGUMENT");
            case A2aError.METHOD_NOT_FOUND ->
                    new Mapping(HttpStatus.NOT_FOUND, "NOT_FOUND", "NOT_FOUND");
            default -> new Mapping(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "INTERNAL");
        };
    }

    private void writeError(HttpServletResponse response, A2aError error) throws IOException {
        Mapping m = mapping(error.getCode());
        Map<String, String> metadata = null;
        if (error.getData() instanceof Map<?, ?> data) {
            metadata = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : data.entrySet()) {
                metadata.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        writeStatusError(response, m.http(), m.status(), m.reason(), error.getMessage(), metadata);
    }

    private void writeStatusError(
            HttpServletResponse response,
            HttpStatus http,
            String status,
            String reason,
            String message)
            throws IOException {
        writeStatusError(response, http, status, reason, message, null);
    }

    private void writeStatusError(
            HttpServletResponse response,
            HttpStatus http,
            String status,
            String reason,
            String message,
            Map<String, String> metadata)
            throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
        info.put("reason", reason);
        info.put("domain", ERROR_DOMAIN);
        if (metadata != null && !metadata.isEmpty()) {
            info.put("metadata", metadata);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", http.value());
        error.put("status", status);
        error.put("message", message);
        error.put("details", List.of(info));
        writeJson(response, http, Map.of("error", error));
    }

    private void writeJson(HttpServletResponse response, HttpStatus http, Object payload)
            throws IOException {
        response.setStatus(http.value());
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(A2aVersion.HEADER, A2aVersion.V1_0.wire());
        response.getWriter().write(mapper.writeValueAsString(payload));
        response.getWriter().flush();
    }
}
