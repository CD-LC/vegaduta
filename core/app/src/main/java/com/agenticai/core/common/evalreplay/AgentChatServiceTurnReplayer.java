package com.agenticai.core.common.evalreplay;

import com.agenticai.core.agents.turn.replay.ReplayTurnRunner;
import com.agenticai.core.quality.dataset.AgentTurnReplayer;
import com.agenticai.core.quality.dataset.EvalSandboxPolicy;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The typed {@link AgentTurnReplayer}: replays an eval case through {@link ReplayTurnRunner}, which runs it
 * through the same turn pipeline every real user message takes. It replaced a reflective bridge into the chat
 * service, so a change to the chat entry points is now a compile error here instead of a per-run failure.
 *
 * <p>It lives in the APP module because that is the only module that sees both sides: {@code core/quality}
 * cannot depend on {@code core/agents} (the cycle its pom documents), and {@code core/agents} must not depend on
 * {@code core/quality}. The same placement as {@code EvalPromotionGateBridge}.
 *
 * <p>The three modes map one to one: text only withholds the agent's tools (a quick run), tools runs the real
 * pipeline in the eval sandbox (readers run, anything that could change something is simulated, a gated call is
 * held without a request or a notification), and tools-with-auto-approval additionally lets the sandbox approve the
 * gated calls it permits. The last is refused HERE too, not only when the run is triggered, unless the operator
 * declared the calling tenant non-production ({@link EvalSandboxPolicy}): this is the place that actually
 * hands out the approval.
 */
@Component
public class AgentChatServiceTurnReplayer implements AgentTurnReplayer {

    private final ReplayTurnRunner runner;
    private final EvalSandboxPolicy policy;

    public AgentChatServiceTurnReplayer(ReplayTurnRunner runner, EvalSandboxPolicy policy) {
        this.runner = runner;
        this.policy = policy;
    }

    @Override
    public ReplayedTurn replay(
        UUID agentId, String sessionId, String subjectKey, String userMessage,
        boolean executeTools, Duration timeout
    ) {
        return replay(new ReplayRequest(agentId, sessionId, subjectKey, userMessage,
            executeTools ? ReplayMode.TOOLS : ReplayMode.TEXT_ONLY, timeout));
    }

    @Override
    public ReplayedTurn replay(ReplayRequest request) {
        String actor = null;
        if (request.mode().autoApproves()) {
            policy.requireAutoApproveAllowed(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            actor = policy.actor();
        }
        ReplayTurnRunner.Result result = runner.run(new ReplayTurnRunner.Request(
            request.agentId(), request.sessionId(), request.subjectKey(), request.userMessage(),
            request.mode().executesTools(), actor, policy.excludedTools(), policy.readOnlyTools(),
            request.timeout()));
        List<ReplayedToolCall> calls = new ArrayList<>();
        for (ReplayTurnRunner.ToolEvidence evidence : result.toolCalls()) {
            calls.add(new ReplayedToolCall(
                evidence.tool(), evidence.input(), evidence.output(), toolStatus(evidence.status())));
        }
        return new ReplayedTurn(result.replyText() == null ? "" : result.replyText(), calls, result.autoApprovedCalls());
    }

    private static ToolStatus toolStatus(ReplayTurnRunner.Status status) {
        return switch (status) {
            case OK -> ToolStatus.OK;
            case FAILED -> ToolStatus.FAILED;
            case NOT_RUN -> ToolStatus.NOT_RUN;
            case AWAITING_APPROVAL -> ToolStatus.AWAITING_APPROVAL;
            case REPLAYED -> ToolStatus.REPLAYED;
            case SIMULATED -> ToolStatus.SIMULATED;
            case UNKNOWN -> ToolStatus.UNKNOWN;
        };
    }
}
