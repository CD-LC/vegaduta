package com.agenticai.core.agents.tools.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code coding_task} - delegate "make this change and get the tests passing" to the coding harness
 * and receive the proposed diff plus a test summary back.
 *
 * <p><b>Two human checkpoints, neither of which the agent can skip.</b> (1) The tool is classified
 * EGRESS + irreversible (so it requires human confirmation) and is on {@code
 * AgentToolGuard.ALWAYS_CONFIRM_TOOLS}, so even a fully autonomous agent parks the call as an
 * approval request instead of starting a sandbox. (2) Whatever the agent does afterwards, the
 * harness waits for the user to approve the exact diff on the Coding tasks page; this tool has no
 * approve, reject or skip parameter and no code path that answers the gate - it only starts a task
 * and reads its state. The returned diff is data for the model to summarise to the user, with
 * {@code approvalRequired} saying so.
 *
 * <p>Identity never travels as an argument: the requester is the tenant context's subject (restored
 * by the approval replay from the original request) and the agent is the one this callback was
 * built for. The budget is the server's default; the model cannot raise it. Failures come back as
 * short text for the model to relay, never as exceptions.
 */
public class CodingTaskToolCallback implements ToolCallback, ToolConfirmationDescriber {

    public static final String TOOL_NAME = CodingTaskToolFactory.TOOL_NAME;
    static final int MAX_DIFF_CHARS_FOR_MODEL = 8000;
    private static final Logger log = LoggerFactory.getLogger(CodingTaskToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final CodingTaskService service;
    private final UUID agentId;

    public CodingTaskToolCallback(CodingTaskService service, UUID agentId) {
        this.service = service;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Delegate a coding change to an isolated sandbox: it clones a public https git repository, edits the "
                                + "code to do what the instruction says, runs the test command and iterates until the tests pass, "
                                + "then returns the diff and a test summary. The user must confirm before it starts, and must "
                                + "approve the diff on the Coding tasks page before anything counts as applied - you cannot "
                                + "approve it yourself. Takes minutes; if it is still running you get a task id to report.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "instruction": {"type": "string", "description": "What to change, written as a standalone task."},
                        "repoUrl": {"type": "string", "description": "Public https git repository URL, e.g. https://github.com/org/repo."},
                        "testCommand": {"type": "string", "description": "One test command: mvn, gradle, npm, pytest or go, e.g. 'mvn -q test', 'npm test', 'pytest -q', 'go test ./...'."}
                    },
                    "required": ["instruction", "repoUrl", "testCommand"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = parseArgs(toolInput);
            String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
            if (subject == null || subject.isBlank()) {
                return "Could not start the coding task: no signed-in user.";
            }
            TaskView started =
                    service.start(
                            new StartInput(
                                    agentId,
                                    text(args, "instruction"),
                                    text(args, "testCommand"),
                                    text(args, "repoUrl"),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            subject);
            TaskView settled =
                    service.awaitForTool(started.taskId(), subject, service.toolWaitSeconds());
            return MAPPER.writeValueAsString(result(settled, subject));
        } catch (IllegalArgumentException | TenantLimitExceededException e) {
            return "Could not start the coding task: " + e.getMessage();
        } catch (ResponseStatusException e) {
            return "Could not start the coding task: "
                    + (e.getReason() == null ? "it is not available right now." : e.getReason());
        } catch (ResourceAccessDeniedException e) {
            return "Could not start the coding task: you do not have access to run this agent.";
        } catch (Exception e) {
            log.warn("coding_task tool call failed", e);
            return "Could not start the coding task: an internal error occurred. Nothing was changed.";
        }
    }

    private ObjectNode result(TaskView t, String subject) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("taskId", t.taskId());
        out.put("status", t.status());
        if (t.outcome() != null) {
            out.put("outcome", t.outcome());
        }
        out.put("message", t.message() == null ? "" : t.message());
        ArrayNode tests = out.putArray("testRuns");
        t.tests()
                .forEach(
                        r -> {
                            ObjectNode n = tests.addObject();
                            n.put("run", r.iteration());
                            n.put("passed", r.exitCode() == 0);
                        });
        if (t.review() != null) {
            out.put("reviewApproved", t.review().approved());
            out.put("reviewNotes", t.review().notes());
        }
        if (CodingTaskDtos.AWAITING_APPROVAL.equals(t.status())
                || CodingTaskDtos.APPROVED.equals(t.status())) {
            try {
                String diff = service.diff(t.taskId(), subject).diff();
                boolean clipped = diff.length() > MAX_DIFF_CHARS_FOR_MODEL;
                out.put("diff", clipped ? diff.substring(0, MAX_DIFF_CHARS_FOR_MODEL) : diff);
                out.put("diffClipped", clipped);
                out.put("filesChanged", t.diffFiles());
                out.put("testFilesTouched", t.testFilesTouched());
            } catch (RuntimeException e) {
                out.put("diff", "");
            }
        }
        if (CodingTaskDtos.AWAITING_APPROVAL.equals(t.status())) {
            out.put("approvalRequired", true);
            out.put(
                    "note",
                    "Nothing is applied yet. Tell the user to review the diff and approve or reject it on the "
                            + "Coding tasks page. You cannot approve it.");
        } else if (!CodingTaskDtos.isTerminal(t.status())) {
            out.put(
                    "note",
                    "Still running. Tell the user the task id; they can follow it on the Coding tasks page.");
        }
        return out;
    }

    @Override
    public String describeForConfirmation(String toolInput) {
        try {
            JsonNode args = parseArgs(toolInput);
            String instruction = text(args, "instruction");
            String repo = text(args, "repoUrl");
            String test = text(args, "testCommand");
            return "Start a coding task in an isolated sandbox. Repository: "
                    + (repo == null ? "(none)" : repo)
                    + ". Test command: "
                    + (test == null ? "(none)" : test)
                    + ". Task: "
                    + (instruction == null ? "(none)" : clip(instruction, 400))
                    + " The model's code leaves this workspace for the sandbox and the model provider, and nothing "
                    + "is applied until you approve the diff.";
        } catch (RuntimeException e) {
            return null; // fall back to the default summary
        }
    }

    private static JsonNode parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException(
                    "instruction, repoUrl and testCommand are required.");
        }
        try {
            JsonNode args = MAPPER.readTree(toolInput);
            if (args == null || !args.isObject()) {
                throw new IllegalArgumentException("the arguments must be a JSON object.");
            }
            return args;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("the arguments were not valid JSON.");
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
