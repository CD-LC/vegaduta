package com.agenticai.core.common.evalgate;

import com.agenticai.core.agents.version.AgentPromotionGate;
import com.agenticai.core.quality.gate.EvalGateService;
import com.agenticai.core.quality.gate.GateEvaluation;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bridge that satisfies {@code core/agents}' {@link AgentPromotionGate}
 * SPI with {@code core/quality}'s eval-gate implementation.
 *
 * <p>It lives in the APP module because that is the only module allowed to
 * see both sides: {@code core/quality} deliberately cannot depend on
 * {@code core/agents} (the {@code AgentTurnReplayer} javadoc documents that
 * boundary), and {@code core/agents} must not grow a dependency on
 * {@code core/quality} just to ask "may this version be promoted". The SPI
 * interface sits in the consumer module, the data sits in the quality module,
 * and the only thing this class does is translate one record into the other.
 *
 * <p>Fail-CLOSED on evaluation errors is deliberate and documented: when the
 * gate is configured but its evaluation itself throws (schema outage mid-call,
 * corrupt row), the promote surfaces the failure rather than silently slipping
 * past the control a tenant switched on. An UNCONFIGURED gate never reaches
 * the evaluation at all - {@code EvalGateService#evaluate} returns
 * allowed=true before touching the version tables.
 */
@Component
public class EvalPromotionGateBridge implements AgentPromotionGate {

    private final EvalGateService evalGateService;

    public EvalPromotionGateBridge(EvalGateService evalGateService) {
        this.evalGateService = evalGateService;
    }

    @Override
    public GateDecision evaluatePromotion(UUID agentId, UUID versionId) {
        GateEvaluation evaluation = evalGateService.evaluate(agentId, versionId);
        return new GateDecision(
            evaluation.allowed(),
            evaluation.configured(),
            evaluation.enabled(),
            evaluation.reason().name(),
            evaluation.message(),
            evaluation.qualifyingRunId(),
            evaluation.verdict(),
            evaluation.overallScore(),
            evaluation.runFinishedAt());
    }
}
