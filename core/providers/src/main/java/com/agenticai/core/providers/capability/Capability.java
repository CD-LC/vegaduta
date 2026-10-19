package com.agenticai.core.providers.capability;

import java.util.Locale;

/**
 * The platform-level "slots" a provider can serve - the vocabulary of the
 * {@code provider_credential.capabilities} array (V217) and of
 * {@code capability_policy.capability} (V352), and the path key of the
 * generic {@code /api/capabilities/{capability}} surface. A capability is
 * what the platform wants ("a decision model", "text to speech"); a
 * {@code provider_key} is the vendor/product that serves it ("TYPESAFE",
 * "ELEVENLABS"). Tenants configure providers; the platform consumes
 * capabilities. See docs/architecture/GENERIC-PROVIDER-CAPABILITY-REGISTRY-
 * 2026-08-09.md.
 *
 * <p>Only capabilities that have at least one wired provider + consumer are
 * declared here - the design doc's fuller set (CHAT, TTS, STT, TRANSLATE,
 * ...) lands as each of those paths is actually migrated onto the registry,
 * not speculatively.
 */
public enum Capability {
    /**
     * The cheap calibrated decision layer in front of the agent hot paths -
     * today served by TypeSafe's Jev ({@code ProviderKeys#TYPESAFE}) and
     * consumed by {@code QueryComplexityClassifier} and {@code
     * AgentRelevanceSelector} through the {@code DecisionEngine} facade in
     * {@code core/agents}.
     */
    DECISION;

    /** Lowercase wire key used in REST paths and the capability_policy table. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Path-variable parsing for {@code /api/capabilities/{capability}/...} - unknown keys are a clean 400, never a 500. */
    public static Capability fromKey(String key) {
        try {
            return valueOf(key == null ? "" : key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UnknownCapabilityException("Unknown capability '" + key + "'");
        }
    }
}
