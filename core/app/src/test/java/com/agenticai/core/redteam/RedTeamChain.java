package com.agenticai.core.redteam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.AgentTaintPolicy;
import com.agenticai.core.agents.tools.EffectJournal;
import com.agenticai.core.agents.tools.LoggingToolCallback;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.ToolCircuitBreakerService;
import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.TurnToolActivity;
import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.ApprovalGateMode;
import com.agenticai.core.guardrails.GuardedToolCallback;
import com.agenticai.core.guardrails.GuardrailService;
import com.agenticai.core.guardrails.TurnApprovalState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * One simulated agent turn: the REAL guard chain ({@code
 * LoggingToolCallback(GuardedToolCallback(tool))}, the wrapping order {@code AgentToolProvider}
 * uses) with the real taint policy over the built-in tool classifications, a real effect journal
 * and tool ledger; only the stores (approval gate, tool-call log, audit) are mocks. The fake tools
 * are attacker-controlled.
 */
final class RedTeamChain {

    static final String SESSION = "redteam-session";
    static final String TURN = "redteam-turn";

    final UUID agentId;
    final AtomicInteger stepCounter = new AtomicInteger();
    final TurnApprovalState approval = new TurnApprovalState("c1:redteam");
    final TurnToolActivity activity = new TurnToolActivity();
    final EffectJournal journal;
    final ToolCircuitBreakerService breaker = mock(ToolCircuitBreakerService.class);
    final HumanApprovalGateService gate = mock(HumanApprovalGateService.class);
    final ApprovalPolicyService policy = mock(ApprovalPolicyService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final AgentTaintPolicy taint;

    final ToolLedgerProperties ledgerLimits = new ToolLedgerProperties();
    private final com.agenticai.core.guardrails.GuardrailService guardrails =
            mock(GuardrailService.class);

    /**
     * The next turn of the same conversation: new step counter, journal and approval state; same
     * agent, session and taint policy.
     */
    RedTeamChain(RedTeamChain prior) {
        this(prior.taint, prior.agentId);
    }

    RedTeamChain(String taintMode, String getAllowHosts) {
        this(null, UUID.randomUUID(), taintMode, getAllowHosts);
    }

    private RedTeamChain(AgentTaintPolicy shared, UUID agent) {
        this(shared, agent, null, null);
    }

    @SuppressWarnings("unchecked")
    private RedTeamChain(
            AgentTaintPolicy shared, UUID agent, String taintMode, String getAllowHosts) {
        this.agentId = agent;
        journal = new EffectJournal(true, 30, 5000, "memory", mock(ObjectProvider.class));
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        when(gate.recordToolCallForApproval(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(request);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
                .thenAnswer(i -> i.getArgument(1));
        taint =
                shared != null
                        ? shared
                        : new AgentTaintPolicy(
                                taintMode,
                                getAllowHosts,
                                provider(builtInRegistry()),
                                provider(null),
                                provider(audit));
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static ToolClassificationRegistry builtInRegistry() {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolve(anyString()))
                .thenAnswer(
                        inv -> {
                            String name = inv.getArgument(0);
                            ToolClassification declared =
                                    BuiltInToolClassifications.all().get(name);
                            return declared != null
                                    ? new ToolClassificationDecision.Classified(declared)
                                    : new ToolClassificationDecision.Blocked(
                                            name,
                                            ToolClassificationDecision.Reason.UNCLASSIFIED,
                                            "unclassified");
                        });
        return registry;
    }

    /** A fake tool whose output is whatever the attacker chose. */
    static ToolCallback fakeTool(String name, String output) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name(name)
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(cb.call(anyString())).thenReturn(output);
        return cb;
    }

    /** The production wrapping of one raw tool for this turn. */
    ToolCallback wrap(ToolCallback raw) {
        GuardedToolCallback guarded =
                new GuardedToolCallback(
                                raw,
                                stepCounter,
                                50,
                                guardrails,
                                policy,
                                gate,
                                agentId,
                                SESSION,
                                null,
                                false,
                                null,
                                null,
                                ApprovalGateMode.POLICY_DEFAULT,
                                null,
                                null,
                                null,
                                null,
                                null)
                        .withTurnApproval(approval);
        var gateForTurn = taint.gateFor(stepCounter, agentId, SESSION);
        if (gateForTurn != null) {
            guarded.withTaintGate(gateForTurn);
        }
        return new LoggingToolCallback(
                        guarded,
                        mock(ToolCallLogRepository.class),
                        agentId,
                        SESSION,
                        provider(null),
                        breaker,
                        null,
                        journal,
                        name -> true,
                        TURN,
                        new SimpleMeterRegistry())
                .withActivity(activity)
                .withLedger(ledgerLimits, approval, null);
    }

    List<ToolLedger.Entry> ledger() {
        return activity.ledger().snapshot().entries();
    }

    boolean journaled(String tool, String args) {
        return journal.lookup(EffectJournal.keyFor(SESSION, TURN, tool, args)).isPresent();
    }

    /**
     * Every approval request recorded: {actionType, toolName, summary, toolInput, requestedBy,
     * agentId, sessionId, digest, memorySessionId}.
     */
    List<Object[]> recordedApprovals() {
        return Mockito.mockingDetails(gate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("recordToolCall"))
                .map(i -> i.getArguments())
                .toList();
    }
}
