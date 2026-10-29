package com.agenticai.core.agents.tools.backgroundjob;

import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code continue_in_background} - hand the rest of a task to a background agent job when one
 * reply's tool-call budget cannot finish it.
 *
 * <p><b>Why this exists.</b> A chat turn has a hard tool-call ceiling ({@code
 * app.guardrails.max-tool-calls-per-turn}); past it every call is answered "budget exhausted,
 * answer now". A task like "find 20+ X" needs dozens of calls - this tool is the legitimate way to
 * keep going: the SAME agent re-runs as a multi-turn {@code agent_job} (each turn still under the
 * same per-turn ceiling and the same approval gates), keeps a durable progress trail, and its
 * result lands in the console's job view instead of a partial chat answer.
 *
 * <p><b>Identity never travels as an argument:</b> the agent is the one this callback was built
 * for, the requester is the tenant context's subject, and the job's session is derived from its own
 * id. The model restates the goal self-contained - the job's first turn sees ONLY the goal text,
 * never this conversation - so the schema tells it to fold in what it already found.
 *
 * <p>Not offered inside a job session (the resolver checks {@code AgentJobSession}): a background
 * job must never spawn another one - an agent that could re-hand-off indefinitely would defeat
 * every turn ceiling by construction.
 */
public class BackgroundJobToolCallback implements ToolCallback {

    /** The per-agent allow-list entry that opts an agent in. */
    public static final String TOOL_NAME =
            com.agenticai.core.agents.tools.scheduling.BackgroundJobToolFactory.TOOL_NAME;

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int GOAL_MAX = 8000;

    private final AgentJobService jobService;
    private final UUID agentId;

    public BackgroundJobToolCallback(AgentJobService jobService, UUID agentId) {
        this.jobService = jobService;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Continue this task as a background job when it needs more steps than a single reply allows "
                                + "(a chat reply has a limited tool-call budget; a job runs several turns in a row). "
                                + "Use it when you can see the work will take many more calls - do NOT burn calls retrying first. "
                                + "Write 'goal' as a standalone instruction: the background run cannot see this conversation, so "
                                + "include what you already found, what remains, and how the user will know it is done. "
                                + "It keeps running under the same per-turn limits and approval rules until the goal is met or its "
                                + "turn allowance is spent, and the user watches it live on the Jobs page.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "goal": {"type": "string", "description": "The whole task, standalone: what to do, what you already learned, and what a good final answer looks like. The background run sees ONLY this text."},
                        "turns": {"type": "integer", "description": "Optional: how many turns the job may run (each turn gets a fresh tool budget). Omit for the platform default; the service clamps it to the per-job cap."}
                    },
                    "required": ["goal"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "" : toolInput);
            String goal = args.path("goal").asText("").trim();
            if (goal.isEmpty()) {
                return "Could not start a background job: 'goal' is required - write the task as a "
                        + "standalone instruction, including what you already found.";
            }
            if (goal.length() > GOAL_MAX) {
                goal = goal.substring(0, GOAL_MAX);
            }
            Integer turns =
                    args.hasNonNull("turns") && args.get("turns").isInt()
                            ? args.get("turns").asInt()
                            : null;

            AgentJobResponse job =
                    jobService.submit(
                            agentId, new AgentJobStartRequest(goal, null, null, null, turns));
            ObjectNode out = MAPPER.createObjectNode();
            out.put("submitted", true);
            out.put("jobId", job.id().toString());
            out.put("status", job.status().name());
            out.put("maxTurns", job.maxTurns());
            out.put(
                    "note",
                    "The job is queued and runs by itself; tell the user it is underway and they can "
                            + "watch progress and the final result under Jobs. Then answer with what "
                            + "you already have - do not wait on it.");
            return MAPPER.writeValueAsString(out);
        } catch (IllegalArgumentException
                | TenantLimitExceededException
                | NoSuchElementException
                | ResourceAccessDeniedException e) {
            return "Could not start a background job: " + e.getMessage();
        } catch (Exception e) {
            log.warn("continue_in_background failed for agent {}: {}", agentId, e.toString());
            return "Could not start a background job: " + e.getMessage();
        }
    }
}
