package com.agenticai.core.agents.collab;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.DelegateOptions;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Ad hoc parallel sub-tasks: the calling agent writes a self-contained brief per piece of work and
 * each brief runs as an isolated worker turn of THAT SAME agent (same provider, model, prompt and
 * tool allow-list), all at once, with the answers joined and handed back. Unlike {@link
 * DelegateToTeamToolCallback} no collaborator agents have to exist or be attached first.
 *
 * <p><b>Isolation:</b> every worker runs {@code AgentChatService#delegateCall} - a throwaway
 * session {@code delegated:subtasks:<runId>:<i>}, no history, no memory, the depth-1 cap (a
 * delegated turn never resolves delegation tools, so a worker cannot fan out again), and the worker
 * tool set below. Workers see only their brief plus a short directive.
 *
 * <p><b>Worker tools:</b> the agent's normal tools minus {@link #workerMayUse the excluded set}:
 * every delegation tool, {@code schedule_task}, {@code request_human}, {@code coding_task} and the
 * write-capable {@code local_shell}. Reads, web and workspace-read tools stay. Approval gating of
 * the remaining tools is unchanged.
 *
 * <p><b>Bounded:</b> at most {@value #MAX_BRANCHES} workers per call, {@value
 * #BRANCH_TIMEOUT_SECONDS}s each, {@value #MAX_ANSWER_CHARS} chars of answer each, and the turn's
 * shared {@link RunBudget} (a branch that is not affordable is reported as skipped). Inside a job
 * session with the step journal on, a worker that already completed for the same brief is not
 * re-run on a resumed attempt.
 */
public class RunSubtasksToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RunSubtasksToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    public static final String TOOL_NAME = "run_subtasks";
    static final int MAX_BRANCHES = 8;
    static final long BRANCH_TIMEOUT_SECONDS = 240;
    static final int MAX_ANSWER_CHARS = 4000;
    static final int MAX_ROLE_CHARS = 80;
    static final String ERR_BRIEF_REQUIRED = "brief is required";

    /** Tools a worker never gets: further delegation, scheduling, asking a human, shell writes. */
    static final Set<String> EXCLUDED_WORKER_TOOLS =
            Set.of(
                    TOOL_NAME,
                    DelegateToTeamToolCallback.TOOL_NAME,
                    "delegate_to_specialist",
                    "schedule_task",
                    "request_human",
                    "coding_task",
                    "local_shell");

    static final String WORKER_DIRECTIVE =
            "You are a worker running one sub-task for another agent. Do only the sub-task in the"
                    + " user message, with the tools you have, and finish it. You cannot ask"
                    + " questions, delegate, schedule or message anyone: if something needed is"
                    + " missing, say exactly what is missing. Reply with the result alone, plainly,"
                    + " in the form the sub-task asks for. Never invent results.";

    /** True when a worker may be offered the tool with this name. */
    static boolean workerMayUse(String toolName) {
        return toolName != null
                && !EXCLUDED_WORKER_TOOLS.contains(toolName)
                && !toolName.startsWith("ask_agent_");
    }

    private final AgentChatService agentChatService;
    private final UUID agentId;
    private final TenantContext capturedContext;
    private final RunBudget runBudget;
    private final StepJournal stepJournal;
    private final String journalRunId;
    private final long branchTimeoutSeconds;

    public RunSubtasksToolCallback(
            AgentChatService agentChatService,
            UUID agentId,
            TenantContext capturedContext,
            RunBudget runBudget,
            StepJournal stepJournal,
            String sessionId) {
        this(
                agentChatService,
                agentId,
                capturedContext,
                runBudget,
                stepJournal,
                sessionId,
                BRANCH_TIMEOUT_SECONDS);
    }

    /**
     * Test seam: the per-worker limit is a constructor value so a timeout can be exercised fast.
     */
    RunSubtasksToolCallback(
            AgentChatService agentChatService,
            UUID agentId,
            TenantContext capturedContext,
            RunBudget runBudget,
            StepJournal stepJournal,
            String sessionId,
            long branchTimeoutSeconds) {
        this.branchTimeoutSeconds = branchTimeoutSeconds;
        boolean durable =
                stepJournal != null
                        && stepJournal.isEnabled()
                        && stepJournal.isResumeEnabled()
                        && sessionId != null
                        && sessionId.startsWith("job:");
        this.agentChatService = agentChatService;
        this.agentId = agentId;
        this.capturedContext = capturedContext;
        this.runBudget = runBudget;
        this.stepJournal = durable ? stepJournal : null;
        this.journalRunId = durable ? sessionId : null;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Runs several independent sub-tasks AT ONCE, each as a separate worker with the same tools"
                                + " as you, and waits for all of them. Use it when the request splits into parts that do"
                                + " not depend on each other (research three companies, check five files, draft four"
                                + " variants). Write each brief so it stands alone: the worker sees only that brief, not"
                                + " this conversation, and cannot ask you anything. State the goal, the inputs it needs"
                                + " and the form of the answer. You get every result back together. Do NOT use it for"
                                + " steps where one result shapes the next, or for a single task: do those yourself."
                                + " Workers cannot delegate, schedule, ask a human or run shell commands.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "tasks": {
                            "type": "array",
                            "description": "The parallel sub-tasks, one entry per worker.",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "brief": {
                                        "type": "string",
                                        "description": "A complete, self-contained instruction for one worker."
                                    },
                                    "role": {
                                        "type": "string",
                                        "description": "Optional short label for the worker, such as 'market researcher'."
                                    }
                                },
                                "required": ["brief"]
                            },
                            "maxItems": 8
                        }
                    },
                    "required": ["tasks"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        List<Map<String, Object>> tasks;
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            Object raw = args.get("tasks");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return "{\"error\": \"tasks is required - a non-empty array of {brief}\"}";
            }
            tasks = new ArrayList<>();
            for (Object o : list) {
                tasks.add(o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            }
        } catch (Exception e) {
            return "{\"error\": \"could not parse tasks - expected {\\\"tasks\\\":[{\\\"brief\\\":\\\"...\\\"}]}\"}";
        }

        String runId = UUID.randomUUID().toString().substring(0, 8);
        int branchCap = Math.min(tasks.size(), MAX_BRANCHES);
        boolean budgeted = runBudget != null && runBudget.isEnabled();
        String estimateText = null;
        String budgetStop = null;
        if (budgeted) {
            estimateText =
                    String.format(
                            Locale.ROOT,
                            "estimated cost up to $%.2f for %d worker(s); run cap $%.2f, about $%.2f already spent",
                            runBudget.estimateUsd(branchCap),
                            branchCap,
                            runBudget.capUsd(),
                            runBudget.spentUsd());
            log.info("run_subtasks pre-run estimate: {}", estimateText);
            int affordable = runBudget.affordableBranches(branchCap);
            if (affordable < branchCap) {
                budgetStop = runBudget.stopMessage();
                branchCap = affordable;
            }
        }
        var emitter = AgentStepEventEmitter.current();
        if (budgetStop != null) {
            emitter.ifPresent(e -> e.guardrail(TOOL_NAME, RunBudget.STOP_REASON));
        }
        List<Map<String, Object>> runnable = tasks.subList(0, branchCap);
        List<Map<String, Object>> results = new ArrayList<>();

        // Trace events are emitted on this thread: the emitter's tool-scope binding is a
        // ThreadLocal that does not reach the workers' virtual threads.
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < runnable.size(); i++) {
            labels.add(label(i, runnable.get(i)));
        }
        emitter.ifPresent(e -> e.swarm("start", runId, labels, null));

        // shutdownNow() in the finally, not close(): close() would block on a worker that ignores
        // interrupts past the timeout (same lifecycle as DelegateToTeamToolCallback).
        @SuppressWarnings("java:S2095")
        ExecutorService pool =
                TenantAwareExecutors.newVirtualThreadPerTaskExecutor("run-subtasks-tool-callback-");
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (int i = 0; i < runnable.size(); i++) {
                Map<String, Object> task = runnable.get(i);
                int index = i;
                futures.add(pool.submit(() -> runBranch(runId, index, task)));
                emitter.ifPresent(e -> e.handoff(e.runAgentId(), agentId));
            }
            for (int i = 0; i < futures.size(); i++) {
                Future<Map<String, Object>> future = futures.get(i);
                try {
                    results.add(future.get(branchTimeoutSeconds, TimeUnit.SECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    Map<String, Object> out = base(i, runnable.get(i));
                    out.put("status", "timeout");
                    out.put("error", "exceeded the " + branchTimeoutSeconds + "s worker limit");
                    results.add(out);
                } catch (Exception e) {
                    Map<String, Object> out = base(i, runnable.get(i));
                    out.put("status", "error");
                    out.put("error", "worker join failed: " + e.getClass().getSimpleName());
                    results.add(out);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        for (int i = runnable.size(); i < tasks.size(); i++) {
            Map<String, Object> out = base(i, tasks.get(i));
            out.put("status", "skipped");
            out.put(
                    "error",
                    budgetStop != null && i < Math.min(tasks.size(), MAX_BRANCHES)
                            ? RunBudget.STOP_REASON + ": " + budgetStop
                            : "over the " + MAX_BRANCHES + "-worker limit");
            results.add(out);
        }
        emitter.ifPresent(e -> e.swarm("finish", runId, null, results));

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            if (estimateText != null) {
                body.put("costEstimate", estimateText);
            }
            if (budgetStop != null) {
                body.put("stopReason", RunBudget.STOP_REASON);
                body.put("stopMessage", budgetStop);
            }
            body.put("results", results);
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"error\": \"subtask result serialization failed\"}";
        }
    }

    private static String roleOf(Map<String, Object> task) {
        Object role = task.get("role");
        if (role == null || role.toString().isBlank()) {
            return null;
        }
        String r = role.toString().trim().replaceAll("\\s+", " ");
        return r.length() > MAX_ROLE_CHARS ? r.substring(0, MAX_ROLE_CHARS) : r;
    }

    private static String label(int index, Map<String, Object> task) {
        String role = roleOf(task);
        return role == null
                ? "subtask-" + (index + 1)
                : "subtask-" + (index + 1) + " (" + role + ")";
    }

    /** The result entry every outcome starts from: 1-based task number and the role, if given. */
    private static Map<String, Object> base(int index, Map<String, Object> task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", index + 1);
        String role = roleOf(task);
        if (role != null) {
            out.put("role", role);
        }
        return out;
    }

    private Map<String, Object> runBranch(String runId, int index, Map<String, Object> task) {
        Map<String, Object> out = base(index, task);
        long started = System.currentTimeMillis();
        Object briefValue = task.get("brief");
        if (briefValue == null || briefValue.toString().isBlank()) {
            out.put("status", "error");
            out.put("error", ERR_BRIEF_REQUIRED);
            return out;
        }
        String brief = briefValue.toString();
        String role = roleOf(task);

        // The plain ThreadLocal does not propagate onto this virtual thread: re-apply the tenant.
        var previous = TenantContext.current();
        if (capturedContext != null) {
            TenantContext.set(capturedContext);
        }
        try {
            String step =
                    stepJournal == null
                            ? null
                            : "subtasks:"
                                    + DelegateToTeamToolCallback.branchHash(
                                            "subtask:" + (role == null ? "" : role), brief, null);
            if (step != null) {
                var done = stepJournal.findCompletedResult(journalRunId, step);
                if (done.isPresent()) {
                    out.put("status", "ok");
                    out.put("answer", done.get());
                    out.put("resumedFromJournal", true);
                    out.put("ms", System.currentTimeMillis() - started);
                    return out;
                }
                stepJournal.begin(journalRunId, step, StepJournal.KIND_SWARM);
            }
            String directive =
                    role == null
                            ? WORKER_DIRECTIVE
                            : WORKER_DIRECTIVE + " Your role: " + role + ".";
            String answer =
                    agentChatService.delegateCall(
                            agentId,
                            brief,
                            null,
                            "delegated:subtasks:" + runId + ":" + index,
                            new DelegateOptions(directive, RunSubtasksToolCallback::workerMayUse));
            String capped =
                    answer != null && answer.length() > MAX_ANSWER_CHARS
                            ? answer.substring(0, MAX_ANSWER_CHARS) + "…"
                            : answer;
            if (step != null) {
                stepJournal.complete(journalRunId, step, StepJournal.KIND_SWARM, capped);
            }
            if (runBudget != null) {
                runBudget.recordDelegate(brief, answer);
            }
            out.put("status", "ok");
            out.put("answer", capped);
            out.put("ms", System.currentTimeMillis() - started);
            return out;
        } catch (Exception e) {
            log.warn("run_subtasks worker {} (agent {}) failed", index, agentId, e);
            out.put("status", "error");
            out.put(
                    "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            out.put("ms", System.currentTimeMillis() - started);
            return out;
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
