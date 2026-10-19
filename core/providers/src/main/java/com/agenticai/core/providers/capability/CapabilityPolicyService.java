package com.agenticai.core.providers.capability;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write over {@link CapabilityPolicy} - the generic-registry version of
 * {@code JevPolicyService}. Schema-per-tenant scoping is structural (the
 * table lives in the tenant schema), so this class never touches {@code
 * TenantContext} itself - callers guard on tenant presence the way {@code
 * JevController} does. What the overrides MEAN is each capability's own
 * concern: this service stores and returns the raw map, {@link
 * CapabilityPolicyValidator} beans own key/type/bounds validation, and
 * consumers ({@code DecisionEngine} for DECISION) decode the fields they
 * read.
 */
@Service
public class CapabilityPolicyService {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final TypeReference<Map<String, Object>> OVERRIDES_TYPE = new TypeReference<>() {
    };

    private final CapabilityPolicyRepository repository;

    public CapabilityPolicyService(CapabilityPolicyRepository repository) {
        this.repository = repository;
    }

    /** The raw entity - empty when this tenant has never written a policy for the capability (identical in behavior to an all-inherited row). */
    @Transactional(readOnly = true)
    public Optional<CapabilityPolicy> current(Capability capability) {
        return repository.findByCapability(capability.key());
    }

    /**
     * The decoded overrides map for the consumers - empty (not an empty map
     * inside a present Optional) when no row exists, so callers can treat
     * "no row" and "nothing overridden" as the same inherit state.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> currentOverrides(Capability capability) {
        return current(capability).map(CapabilityPolicy::getOverrides).map(CapabilityPolicyService::decode);
    }

    /**
     * Upsert: the capability's single row is reused across every PUT
     * (mirroring the credential rotate-in-place posture), so {@code
     * createdAt} survives. Null values in {@code overrides} are stripped
     * before persisting - null and absent are both "inherit," and keeping
     * only set keys keeps the stored document honest.
     */
    @Transactional
    public CapabilityPolicy update(Capability capability, Map<String, Object> overrides) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k != null && v != null) {
                    cleaned.put(k, v);
                }
            });
        }
        String encoded = encode(cleaned);
        CapabilityPolicy policy = repository.findByCapability(capability.key())
            .map(existing -> {
                existing.apply(encoded);
                return existing;
            })
            .orElseGet(() -> new CapabilityPolicy(capability.key(), encoded));
        return repository.save(policy);
    }

    /** The stored jsonb document as a mutable map - never exposes the entity's raw string to callers. */
    public static Map<String, Object> decode(String overrides) {
        if (overrides == null || overrides.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(overrides, OVERRIDES_TYPE);
        } catch (Exception e) {
            // A corrupt row degrades to "no overrides" rather than breaking
            // the capability - same fail-open posture the credential path has.
            return new LinkedHashMap<>();
        }
    }

    private static String encode(Map<String, Object> overrides) {
        try {
            return MAPPER.writeValueAsString(overrides);
        } catch (Exception e) {
            throw new IllegalArgumentException("policy overrides must be JSON-serializable scalars/objects", e);
        }
    }
}
