package com.agenticai.core.agents.version;

/**
 * Thrown when a PRODUCTION promote fails the eval gate - mapped to
 * {@code 412 Precondition Failed} by {@code AgentVersionController}, carrying
 * the gate's decision so the response body IS the gate report (which run,
 * which verdict, why it did not qualify), not a bare refusal.
 *
 * <p>A dedicated type rather than a status-flag exception because the
 * controller needs the structured {@link AgentPromotionGate.GateDecision}
 * back, and because the app-wide {@code GlobalExceptionHandler} would
 * otherwise flatten it to a generic 500 - see
 * {@link AgentVersionNotFoundException}'s javadoc for the handler-ordering
 * story that makes controller-local mapping necessary here.
 */
public class PromotionGateBlockedException extends RuntimeException {

    private final transient AgentPromotionGate.GateDecision decision;

    public PromotionGateBlockedException(AgentPromotionGate.GateDecision decision) {
        super(decision.message());
        this.decision = decision;
    }

    public AgentPromotionGate.GateDecision getDecision() {
        return decision;
    }
}
