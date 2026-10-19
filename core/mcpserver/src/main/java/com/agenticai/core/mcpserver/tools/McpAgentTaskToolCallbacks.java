package com.agenticai.core.mcpserver.tools;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.agents.a2a.A2aError;
import com.agenticai.core.agents.a2a.A2aTask;
import com.agenticai.core.agents.a2a.A2aTaskService;
import com.agenticai.core.agents.a2a.A2aVersion;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.McpCallerContext;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Task-style (start / poll / cancel) variants of {@code call_agent} for work
 * that outlives one MCP request: {@code start_agent_task} returns a task id
 * immediately, {@code get_task} polls it, {@code cancel_task} stops it.
 *
 * <p><b>Why tools and not the MCP spec's native Tasks utility:</b> the spec's
 * task-augmented {@code tools/call} + {@code tasks/get|result|cancel} methods
 * need protocol support from the server SDK (Spring AI 1.1.7's MCP server
 * starter, SYNC type, does not expose them) and hand-rolling them would mean
 * owning the streamable transport. The tool trio here carries the same
 * lifecycle in plain tool results any MCP client can call today, using the
 * spec's state vocabulary: {@code working} (this server also reports
 * {@code submitted} for the instant before the turn starts), {@code completed},
 * {@code failed}, {@code cancelled}. When the SDK gains native Tasks these
 * tools can stay as the fallback.
 *
 * <p>Backed by {@link A2aTaskService}/{@code A2aTaskStore} - the SAME task
 * records the A2A endpoint uses, so the store's TTL ({@code app.a2a.task-ttl}),
 * capacity cap and optional Redis mirroring ({@code app.a2a.task-store=redis})
 * apply unchanged. Ownership is the {@code TenantContext} the MCP auth filter
 * binds (tenant + {@code mcp-external:<grantId>}), so a task id is only
 * readable/cancelable by the grant that started it; anyone else gets the same
 * "not found" as for an unknown id. Agent authorization is the same per-grant
 * exposed-agent check {@code call_agent} performs, BEFORE any turn starts.
 *
 * <p><b>Limits:</b> at most {@code app.mcpserver.async-tasks.max-concurrent-per-tenant}
 * (default {@value #DEFAULT_MAX_CONCURRENT_PER_TENANT}) non-terminal tasks per tenant on this
 * JVM, so one tenant cannot take the whole shared async pool; the count is per replica
 * (document: with N replicas the effective cap is N times that). {@code get_task} polls draw
 * from a separate, larger rate-limit bucket (see {@code McpServerRateLimiter#allowPoll}) and
 * the result carries {@code poll_after_seconds} while the task is still running.
 *
 * <p>Errors are returned as {@code {"error": "..."}} JSON, never thrown, same as
 * {@code call_agent}.
 */
public final class McpAgentTaskToolCallbacks {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTaskToolCallbacks.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    static final String AUDIT_ACTION = "mcp.grant.start_agent_task";
    public static final int DEFAULT_MAX_CONCURRENT_PER_TENANT = 4;
    /** Suggested delay before the next get_task while a task is non-terminal. */
    static final int POLL_AFTER_SECONDS = 3;

    private McpAgentTaskToolCallbacks() {
        // factory only
    }

    public static List<ToolCallback> create(A2aTaskService taskService, AuditLogService auditLogService) {
        return create(taskService, auditLogService, DEFAULT_MAX_CONCURRENT_PER_TENANT);
    }

    /** @param maxConcurrentPerTenant in-flight task cap per tenant on this JVM; {@code <= 0} disables the cap. */
    public static List<ToolCallback> create(
        A2aTaskService taskService, AuditLogService auditLogService, int maxConcurrentPerTenant
    ) {
        return List.of(
            new StartAgentTask(taskService, auditLogService, new TenantTaskCap(maxConcurrentPerTenant)),
            new GetTask(taskService),
            new CancelTask(taskService));
    }

    // -------------------------------------------------------------- tenant cap

    /** Counts non-terminal tasks per tenant; a slot is held from {@link #tryAcquire} until the task completes. */
    static final class TenantTaskCap {

        private final int max;
        /** Entries are removed when their count returns to zero, so the map never outgrows the live tenants. */
        private final ConcurrentHashMap<String, Integer> inFlight = new ConcurrentHashMap<>();

        TenantTaskCap(int max) {
            this.max = max;
        }

        /** @return a releaser (idempotent), or null when the tenant is at its cap. */
        Runnable tryAcquire(String tenantId) {
            if (max <= 0) {
                return () -> { };
            }
            boolean[] acquired = {false};
            inFlight.compute(tenantId, (k, n) -> {
                int current = n == null ? 0 : n;
                if (current >= max) {
                    return n;
                }
                acquired[0] = true;
                return current + 1;
            });
            if (!acquired[0]) {
                return null;
            }
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    inFlight.computeIfPresent(tenantId, (k, n) -> n <= 1 ? null : n - 1);
                }
            };
        }

        int size() {
            return inFlight.size();
        }

        int max() {
            return max;
        }
    }

    // ------------------------------------------------------------------ start

    static final class StartAgentTask implements ToolCallback {

        private final A2aTaskService taskService;
        private final AuditLogService auditLogService;
        private final TenantTaskCap cap;

        StartAgentTask(A2aTaskService taskService, AuditLogService auditLogService, TenantTaskCap cap) {
            this.taskService = taskService;
            this.auditLogService = auditLogService;
            this.cap = cap;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("start_agent_task")
                .description(
                    "Starts a long-running message to one of your organization's agents WITHOUT waiting for the reply. "
                        + "Returns a task_id immediately; poll get_task with it until state is completed, failed or "
                        + "cancelled, or stop it with cancel_task. Use this instead of call_agent when the agent may "
                        + "take minutes. Pass the same session_id on a follow-up to continue the conversation."
                )
                .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "agent_id": {"type": "string", "description": "One of the ids returned by list_my_agents."},
                            "message": {"type": "string", "description": "The message to send to the agent."},
                            "session_id": {"type": "string", "description": "Optional - reuse a prior session_id to continue that conversation."}
                        },
                        "required": ["agent_id", "message"]
                    }
                    """)
                .build();
        }

        @Override
        public String call(String toolInput) {
            McpCallerContext caller = requireCaller("start_agent_task");
            try {
                JsonNode input = MAPPER.readTree(toolInput);
                String agentIdRaw = textOrNull(input, "agent_id");
                String message = textOrNull(input, "message");
                String sessionIdRaw = textOrNull(input, "session_id");
                if (!StringUtils.hasText(agentIdRaw) || !StringUtils.hasText(message)) {
                    return errorJson("agent_id and message are both required.");
                }
                UUID agentId;
                try {
                    agentId = UUID.fromString(agentIdRaw);
                } catch (IllegalArgumentException e) {
                    return errorJson("agent_id is not a valid id - call list_my_agents to see valid ids.");
                }
                if (!caller.isAgentExposed(agentId)) {
                    log.warn("MCP grant {} attempted to start a task on agent {} outside its exposed-agent set - rejected",
                        caller.grantId(), agentId);
                    recordAudit(caller.grantId(), agentIdRaw, AuditOutcome.FAILURE,
                        "MCP grant " + caller.grantId() + " attempted to start a task on agent " + agentIdRaw
                            + ", which is outside its exposed-agent set.");
                    return errorJson("This API key is not authorized to call that agent - call list_my_agents to see which agents you can use.");
                }
                String sessionId = StringUtils.hasText(sessionIdRaw)
                    ? sessionIdRaw
                    : "mcp-" + caller.grantId() + "-" + UUID.randomUUID();
                String tenantKey = TenantContext.current().map(TenantContext::getTenantId).orElse("_none");
                Runnable release = cap.tryAcquire(tenantKey);
                if (release == null) {
                    return errorJson("Too many tasks are already running for your organization (limit "
                        + cap.max() + " at once) - wait for one to finish or cancel_task one, then retry.");
                }
                A2aTask task;
                try {
                    task = taskService.create(agentId, sessionId, UUID.randomUUID().toString(), message, null);
                } catch (RuntimeException e) {
                    release.run();
                    throw e;
                }
                // Every terminal path (completed, failed, canceled, aborted, TTL-swept) completes this future.
                task.completion().whenComplete((ignored, err) -> release.run());
                try {
                    taskService.runAsync(task);
                } catch (A2aError busy) {
                    return errorJson("Too many tasks are in flight on this server - retry shortly.");
                }
                recordAudit(caller.grantId(), agentId.toString(), AuditOutcome.SUCCESS,
                    "MCP grant " + caller.grantId() + " started task " + task.id() + " on agent " + agentId
                        + " (session " + sessionId + ").");
                return MAPPER.writeValueAsString(taskJson(task));
            } catch (Exception e) {
                log.warn("start_agent_task failed for grant {}", caller.grantId(), e);
                return errorJson("Could not start the task: " + e.getMessage());
            }
        }

        private void recordAudit(UUID grantId, String agentIdOrNull, AuditOutcome outcome, String detail) {
            try {
                auditLogService.record("mcp-external:" + grantId, AUDIT_ACTION, "Agent", agentIdOrNull, outcome, detail);
            } catch (RuntimeException auditFailure) {
                log.warn("Failed to record audit log entry for MCP grant {} start_agent_task", grantId, auditFailure);
            }
        }
    }

    // -------------------------------------------------------------------- get

    static final class GetTask implements ToolCallback {

        private final A2aTaskService taskService;

        GetTask(A2aTaskService taskService) {
            this.taskService = taskService;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("get_task")
                .description(
                    "Returns the current state of a task started with start_agent_task: state is one of submitted, "
                        + "working, completed, failed, cancelled. reply holds the agent's output (partial while working "
                        + "on the server that runs it); error is set when failed. While the task is still running the result "
                        + "carries poll_after_seconds - wait that long before polling again."
                )
                .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "task_id": {"type": "string", "description": "The task_id returned by start_agent_task."}
                        },
                        "required": ["task_id"]
                    }
                    """)
                .build();
        }

        @Override
        public String call(String toolInput) {
            requireCaller("get_task");
            try {
                String taskId = textOrNull(MAPPER.readTree(toolInput), "task_id");
                if (!StringUtils.hasText(taskId)) {
                    return errorJson("task_id is required.");
                }
                return MAPPER.writeValueAsString(taskJson(taskService.get(taskId, null)));
            } catch (A2aError notFound) {
                return errorJson("Task not found (unknown, expired, or not started by this API key).");
            } catch (Exception e) {
                log.warn("get_task failed", e);
                return errorJson("Could not read the task: " + e.getMessage());
            }
        }
    }

    // ----------------------------------------------------------------- cancel

    static final class CancelTask implements ToolCallback {

        private final A2aTaskService taskService;

        CancelTask(A2aTaskService taskService) {
            this.taskService = taskService;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("cancel_task")
                .description("Cancels a still-running task started with start_agent_task. A task that already finished cannot be cancelled.")
                .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "task_id": {"type": "string", "description": "The task_id returned by start_agent_task."}
                        },
                        "required": ["task_id"]
                    }
                    """)
                .build();
        }

        @Override
        public String call(String toolInput) {
            requireCaller("cancel_task");
            try {
                String taskId = textOrNull(MAPPER.readTree(toolInput), "task_id");
                if (!StringUtils.hasText(taskId)) {
                    return errorJson("task_id is required.");
                }
                return MAPPER.writeValueAsString(taskJson(taskService.cancel(taskId, null)));
            } catch (A2aError e) {
                if (e.getCode() == A2aError.TASK_NOT_CANCELABLE) {
                    return errorJson("Task already finished and cannot be cancelled - call get_task for its result.");
                }
                return errorJson("Task not found (unknown, expired, or not started by this API key).");
            } catch (Exception e) {
                log.warn("cancel_task failed", e);
                return errorJson("Could not cancel the task: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static McpCallerContext requireCaller(String tool) {
        return McpCallerContext.current().orElseThrow(() -> new IllegalStateException(
            tool + " invoked with no McpCallerContext set - McpApiKeyAuthFilter should have rejected this "
                + "request before it ever reached tool dispatch."));
    }

    /** Spec-vocabulary state names: the A2A 0.3 wire form is already lower-case "canceled"; MCP spells it "cancelled". */
    static String stateName(A2aTask task) {
        String wire = task.snapshot().state().wire(A2aVersion.V0_3);
        return "canceled".equals(wire) ? "cancelled" : wire;
    }

    static Map<String, Object> taskJson(A2aTask task) {
        A2aTask.Snapshot snap = task.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", task.id());
        out.put("state", stateName(task));
        out.put("session_id", task.contextId());
        out.put("created_at", task.createdAt().toString());
        out.put("updated_at", snap.timestamp().toString());
        out.put("reply", snap.reply() == null ? "" : snap.reply());
        if (snap.failureMessage() != null) {
            out.put("error", snap.failureMessage());
        }
        if (!task.isTerminal()) {
            out.put("poll_after_seconds", POLL_AFTER_SECONDS);
        }
        return out;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
        }
    }
}
