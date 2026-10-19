package com.agenticai.core.providers.capability;

import java.time.Instant;
import java.util.Map;

/**
 * {@code GET/PUT /api/capabilities/{capability}/policy} body - the raw
 * overrides map echoed verbatim so the console can render "Inherit"
 * distinctly from an explicit value (an absent key IS inherit; there is no
 * delete). {@code updatedAt} is null when no row exists.
 */
public record CapabilityPolicyResponse(
    String capability,
    Map<String, Object> overrides,
    Instant updatedAt
) {
    public static CapabilityPolicyResponse of(Capability capability, CapabilityPolicy policy) {
        return new CapabilityPolicyResponse(
            capability.key(),
            CapabilityPolicyService.decode(policy.getOverrides()),
            policy.getUpdatedAt()
        );
    }

    /** The no-row response: empty overrides = fully inheriting platform defaults. */
    public static CapabilityPolicyResponse inherit(Capability capability) {
        return new CapabilityPolicyResponse(capability.key(), Map.of(), null);
    }
}
