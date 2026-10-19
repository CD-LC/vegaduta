package com.agenticai.core.rl.api;

import com.agenticai.core.rl.domain.PolicyDimension;
import java.util.UUID;

/**
 * The write-side counterpart to {@link RlPolicyPort}: whatever module actually
 * <em>consumes</em> an arm at runtime gets to veto an arm before it is ever
 * persisted.
 *
 * <p>Why this is an SPI rather than a service inside {@code core/rl}: the only
 * consumer of a {@link PolicyDimension#MODEL_ROUTE} arm is {@code
 * AgentChatService} in {@code core/agents}, and it applies the arm's {@code
 * {"model":"..."}} on top of the AGENT's own {@code providerName} - so deciding
 * whether an arm is safe needs the agent row, the tenant's provider
 * credentials, and that provider's model catalog, all of which live in {@code
 * core/agents}/{@code core/providers}. {@code core/agents} already depends on
 * {@code core/rl} (it consumes {@link RlPolicyPort}), so the dependency can
 * only point one way: {@code rl} declares the interface, {@code agents}
 * implements it. That also keeps read side and write side in the same file
 * neighbourhood - the implementation calls {@code AgentChatService}'s OWN arm
 * config reader, so the two provably agree on the config shape.
 *
 * <p>Implementations throw {@link RlArmRejectedException} (mapped to 400) for a
 * config this tenant cannot safely use, and are free to let a module-native
 * "agent not found"/"access denied" exception propagate for an agent id the
 * caller has no business touching.
 *
 * <p>Resolved through an {@code ObjectProvider} by {@code
 * PolicyArmAdminService}, matching the optional cross-module posture {@code
 * AgentChatService} uses for {@link RlPolicyPort} in the other direction. When
 * no implementation is on the classpath, arm creation FAILS CLOSED for {@code
 * MODEL_ROUTE} - an unvalidated model-route arm is exactly the thing this
 * interface exists to prevent.
 */
public interface RlArmConfigValidator {

    /**
     * Validates one proposed arm, throwing if it must not be persisted.
     *
     * @param agentId       the agent whose policy the arm would join - implementations
     *                      should assert it exists and is reachable by the caller
     * @param dimension     the policy dimension the arm belongs to
     * @param armKey        the operator-supplied arm key (already trimmed/length-checked)
     * @param armConfigJson the arm config, already proven to be a JSON object by the caller
     */
    void validateArm(UUID agentId, PolicyDimension dimension, String armKey, String armConfigJson);
}
