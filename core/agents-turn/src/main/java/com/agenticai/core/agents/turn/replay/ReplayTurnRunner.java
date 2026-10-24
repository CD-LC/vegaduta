package com.agenticai.core.agents.turn.replay;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.SandboxApprovalGrants;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Runs ONE user message through the SAME turn pipeline a real user's message takes ({@link
 * AgentChatService#chat(TurnContext, ChatTurnRequest)}: tenant gate, moderation, memory, tool
 * resolution with the full guard chain, step limits, budget metering) for an offline caller such as the
 * eval runner, and hands back what the turn did as evidence.
 *
 * <p>Two modes, chosen by the caller and never inferred:
 * <ul>
 *   <li><b>text only</b> ({@link Request#withTools} false): the agent's tool set is withheld
 *       ({@code suppressAgentTools}), so the model can only answer in text. The same turn otherwise.
 *   <li><b>with tools</b>: the agent's tools are offered and the turn runs the whole pipeline (tool selection,
 *       guards, approval gate, step ceilings, budget), but in an <b>isolated sandbox</b> registered under this turn's
 *       own, server-generated request id for the duration of the turn only ({@link SandboxApprovalGrants#registerSandbox}):
 *       <ul>
 *         <li>a tool runs for real only when {@link EvalToolEffectPolicy} says it is a reader (by its
 *             classification); every other call is answered with a simulated result, so a test never changes data,
 *             sends a message or spends money; a tool that cannot be made safe is therefore simulated and reported;
 *         <li>a call that needs a human is held exactly as in production, but no approval request is recorded, so
 *             nobody is notified; unless the caller asked for {@link Request#autoApproveActor an auto-approval},
 *             which approves it (never for the excluded tools) and is recorded by the turn journal as
 *             {@code eval-approved}.
 *       </ul>
 *       A caller that asks for tools without an effect policy available is refused: running tools unsandboxed is not
 *       a fallback.
 * </ul>
 *
 * <p><b>Evidence.</b> The tool calls come from the turn's own record, not from a reply parser: the
 * turn's tool ledger (what each call ended as: ran, failed, not run, held for approval, replayed) joined
 * to the tool-call log rows (the full arguments and result). A call a guard answered instead of running
 * is therefore reported as such, rather than as a call that succeeded.
 *
 * <p>This class decides nothing about WHO may auto-approve: that is the caller's policy (the eval runner
 * checks the operator's non-production tenant list before it asks for it). It requires a tenant context
 * on the calling thread, like every chat entry.
 */
@Service
public class ReplayTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(ReplayTurnRunner.class);

    private final AgentChatService chatService;
    private final ToolCallLogService toolCallLogService;
    private final ObjectProvider<EvalToolEffectPolicy> effects;

    public ReplayTurnRunner(
            AgentChatService chatService,
            ToolCallLogService toolCallLogService,
            ObjectProvider<EvalToolEffectPolicy> effects) {
        this.chatService = chatService;
        this.toolCallLogService = toolCallLogService;
        this.effects = effects;
    }

    /**
     * @param sessionId a unique session per replay, so memory never carries one replay into the next
     * @param subjectKey the human the turn is metered against
     * @param autoApproveActor null for a turn that parks gated calls as in production; otherwise the
     *     name the auto-approval is attributed to (only meaningful with {@code withTools})
     * @param autoApproveExcludedTools tool names or {@code *} globs that stay gated even under the grant
     * @param readOnlyTools tool names or {@code *} globs the operator vouches are read-only; each lets a tool run
     *     for real only if it has no declared classification (see {@link EvalToolEffectPolicy})
     */
    public record Request(
            UUID agentId,
            String sessionId,
            String subjectKey,
            String userMessage,
            boolean withTools,
            String autoApproveActor,
            Collection<String> autoApproveExcludedTools,
            Collection<String> readOnlyTools,
            Duration timeout) {

        /** A request that vouches for no unclassified tool. */
        public Request(
                UUID agentId,
                String sessionId,
                String subjectKey,
                String userMessage,
                boolean withTools,
                String autoApproveActor,
                Collection<String> autoApproveExcludedTools,
                Duration timeout) {
            this(agentId, sessionId, subjectKey, userMessage, withTools, autoApproveActor,
                    autoApproveExcludedTools, List.of(), timeout);
        }
    }

    /** How one tool call ended, in the ledger's terms; {@code UNKNOWN} when no ledger was recorded. */
    public enum Status {
        OK,
        FAILED,
        NOT_RUN,
        AWAITING_APPROVAL,
        REPLAYED,
        /** The sandbox answered with a simulated result instead of running the tool (it could change something). */
        SIMULATED,
        UNKNOWN
    }

    /** One tool call of the turn: the full arguments and result where the log has them. */
    public record ToolEvidence(String tool, String input, String output, Status status) {}

    /**
     * @param autoApprovedCalls how many gated calls the sandbox grant let through (0 without a grant)
     * @param omittedCalls calls past the ledger's per-turn limit that are not listed in {@code toolCalls}
     * @param simulatedTools the tool names of the calls the sandbox simulated instead of running, in order
     * @param heldTools the tool names of the calls held for approval without a request being recorded, in order
     */
    public record Result(
            String replyText,
            List<ToolEvidence> toolCalls,
            int autoApprovedCalls,
            int omittedCalls,
            List<String> simulatedTools,
            List<String> heldTools) {

        public Result {
            simulatedTools = simulatedTools == null ? List.of() : List.copyOf(simulatedTools);
            heldTools = heldTools == null ? List.of() : List.copyOf(heldTools);
        }

        public Result(String replyText, List<ToolEvidence> toolCalls, int autoApprovedCalls, int omittedCalls) {
            this(replyText, toolCalls, autoApprovedCalls, omittedCalls, List.of(), List.of());
        }
    }

    public Result run(Request request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.timeout(), "a replay needs the time it may wait for the reply");
        TenantContext tenant = TenantContext.current().orElseThrow(() ->
                new IllegalStateException("A replayed turn needs a tenant context on the calling thread."));
        ChatTurnRequest turn =
                ChatTurnRequest.builder(
                                request.agentId(), request.sessionId(), request.subjectKey(), request.userMessage())
                        .suppressAgentTools(!request.withTools())
                        .build();
        // The request id is generated HERE: it is the key of the sandbox grant, so nothing a client sends
        // can ever name it.
        TurnContext context =
                new TurnContext(
                        tenant,
                        request.agentId(),
                        request.sessionId(),
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        null,
                        null);

        SandboxApprovalGrants.Registration registration = request.withTools() ? sandboxFor(context, request) : null;
        String reply;
        try {
            List<String> chunks = chatService
                    .chat(context, turn)
                    .collectList()
                    .block(request.timeout());
            reply = chunks == null ? "" : String.join("", chunks);
        } finally {
            if (registration != null) {
                registration.close();
            }
        }

        ToolLedger ledger = context.toolActivity().ledger().snapshot();
        List<String> simulated = registration == null ? List.of() : registration.grant().simulatedTools();
        List<String> held = registration == null ? List.of() : registration.grant().heldTools();
        List<ToolEvidence> evidence = request.withTools()
                ? evidence(ledger, loggedCalls(request), simulated)
                : List.of();
        int autoApproved = registration == null ? 0 : registration.grant().usedCount();
        return new Result(reply, evidence, autoApproved, ledger.omitted(), simulated, held);
    }

    /**
     * The isolation of a tools turn. Refused when there is no effect policy to isolate with: a replay with tools
     * must never run unsandboxed because a bean was missing.
     */
    private SandboxApprovalGrants.Registration sandboxFor(TurnContext context, Request request) {
        EvalToolEffectPolicy policy = effects == null ? null : effects.getIfAvailable();
        if (policy == null) {
            throw new IllegalStateException(
                    "An eval turn with tools needs the sandbox that decides which tools may run, and none is available:"
                            + " refusing to run the agent's tools unsandboxed.");
        }
        String actor = request.autoApproveActor() == null || request.autoApproveActor().isBlank()
                ? null
                : request.autoApproveActor();
        return SandboxApprovalGrants.registerSandbox(
                context.requestId(),
                actor,
                request.autoApproveExcludedTools(),
                policy.forRun(request.readOnlyTools()));
    }

    private List<ToolCallLog> loggedCalls(Request request) {
        try {
            return toolCallLogService.findByAgentIdAndSessionId(request.agentId(), request.sessionId());
        } catch (RuntimeException e) {
            log.warn("Could not read the tool-call log of replay session {} ({}): evidence falls back to the ledger",
                    request.sessionId(), e.toString());
            return List.of();
        }
    }

    /**
     * The ledger is the authority on WHICH calls the turn made and how each ended; the log rows add the
     * full arguments and result (the ledger keeps them cut and compacted), and are joined only when they
     * line up one for one by tool name. With no ledger (switched off) the log rows alone are reported,
     * status unknown unless the row carries an error.
     */
    static List<ToolEvidence> evidence(ToolLedger ledger, List<ToolCallLog> rows) {
        return evidence(ledger, rows, List.of());
    }

    /**
     * As above; {@code simulatedTools} (the sandbox's own list) names the calls it simulated, which only matters
     * with no ledger: then a log row of such a tool is reported {@link Status#SIMULATED}, one per listed call.
     */
    static List<ToolEvidence> evidence(ToolLedger ledger, List<ToolCallLog> rows, List<String> simulatedTools) {
        List<ToolEvidence> out = new ArrayList<>();
        List<ToolLedger.Entry> entries = ledger == null ? List.of() : ledger.entries();
        if (entries.isEmpty()) {
            java.util.Map<String, Integer> simulated = new java.util.HashMap<>();
            simulatedTools.forEach(name -> simulated.merge(name, 1, Integer::sum));
            for (ToolCallLog row : rows) {
                boolean failed = row.getErrorMessage() != null && !row.getErrorMessage().isBlank();
                Status status = failed ? Status.FAILED : Status.UNKNOWN;
                if (!failed && simulated.merge(String.valueOf(row.getToolName()), -1, Integer::sum) >= 0) {
                    status = Status.SIMULATED;
                }
                out.add(new ToolEvidence(row.getToolName(), row.getToolInput(), row.getToolOutput(), status));
            }
            return out;
        }
        boolean joinable = rows.size() == entries.size();
        for (int i = 0; i < entries.size(); i++) {
            joinable = joinable && entries.get(i).tool().equals(rows.get(i).getToolName());
        }
        for (int i = 0; i < entries.size(); i++) {
            ToolLedger.Entry entry = entries.get(i);
            String input = entry.arguments();
            String output = entry.result();
            if (joinable) {
                input = rows.get(i).getToolInput();
                output = rows.get(i).getToolOutput();
            }
            out.add(new ToolEvidence(entry.tool(), input, output, map(entry.status())));
        }
        return out;
    }

    private static Status map(ToolLedger.Status status) {
        if (status == null) {
            return Status.UNKNOWN;
        }
        return switch (status) {
            case OK -> Status.OK;
            case FAILED -> Status.FAILED;
            case NOT_RUN -> Status.NOT_RUN;
            case AWAITING_APPROVAL -> Status.AWAITING_APPROVAL;
            case REPLAYED -> Status.REPLAYED;
            case SIMULATED -> Status.SIMULATED;
        };
    }
}
