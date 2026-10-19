package com.agenticai.core.providers.capability;

import java.util.Map;

/**
 * Per-capability schema check for {@code PUT /api/capabilities/{capability}
 * /policy} bodies. The {@code capability_policy.overrides} column is a free
 * jsonb map (every capability's knobs differ), so key/type/bounds validation
 * is delegated to the capability owner: one bean per capability, collected
 * via {@code List} injection in {@link CapabilityController}. A capability
 * with no registered validator rejects policy writes - accepting an
 * unvalidated map would silently let a tenant set knobs nothing reads.
 *
 * <p>Contract for validators: every field in {@code overrides} is a nullable
 * override where {@code null}/absent means "inherit the platform default";
 * throw {@link IllegalArgumentException} (mapped to 400) on unknown keys,
 * wrong types, or out-of-range values.
 */
public interface CapabilityPolicyValidator {

    /** The {@link Capability#key()} this validator owns - e.g. {@code "decision"}. */
    String capability();

    /** Throws {@link IllegalArgumentException} on an invalid overrides map. */
    void validateOverrides(Map<String, Object> overrides);
}
