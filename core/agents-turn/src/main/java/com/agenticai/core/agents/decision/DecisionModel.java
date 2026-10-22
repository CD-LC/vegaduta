package com.agenticai.core.agents.decision;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The provider SPI for the DECISION capability - what {@link DecisionEngine} routes to, and the
 * only thing a new decision-model vendor has to implement ("one validator + one impl + a catalog
 * entry," per the generic-registry design doc). Consumers ({@code QueryComplexityClassifier},
 * {@code AgentRelevanceSelector}) never see this interface - they talk to {@link DecisionEngine},
 * which picks the active provider and delegates.
 *
 * <p>The contract is deliberately not "chat": a decision model answers typed questions about a
 * {@code state} - calibrated yes/no probabilities ({@link #noul}/{@link #nouls}), a closed-set
 * winner ({@link #choose}), an ordered weighted {@link #score} - and every method is fail-open:
 * unavailable, misconfigured, or upstream failure all return empty rather than throwing, because
 * every call site sits in front of real traffic with a heuristic to fall back to. The one
 * deliberate exception is {@link #playground}, which is fail-VISIBLE: a tenant experimenting needs
 * to see their key fail.
 *
 * <p>Impl #1 is {@link JevDecisionService} (TypeSafe's System One API). A second vendor implements
 * this interface plus a {@code ProviderCredentialValidator} bean - no tables, controllers, routes,
 * or consumer edits.
 */
public interface DecisionModel {

    /**
     * The {@code provider_credential.provider_key} whose tenant row funds this model - e.g. {@code
     * "TYPESAFE"}. The engine uses it to prefer a model the tenant is paying for over one riding
     * the platform key.
     */
    String providerKey();

    /** The provider's own platform kill-switch (e.g. {@code app.jev.enabled}). */
    boolean isEnabled();

    /** Enabled AND a usable key resolves for the current request's tenant. */
    boolean isAvailable();

    /** Which key source the current request resolves to - empty when unavailable. */
    Optional<KeySource> keySource();

    /**
     * One yes/no question answered as a probability in [0,1]. Empty when unavailable or on any
     * failure.
     */
    Optional<Double> noul(String state, String instructions);

    /** Several yes/no questions in one call - missing/failed answers are absent from the map. */
    Map<String, Double> nouls(String state, Map<String, String> instructionsByKey);

    /**
     * Pick a winner over {@code criteria} (option key -> description). Empty when unavailable, on
     * failure, or when the winner isn't an offered option.
     */
    Optional<Choice> choose(String state, String instructions, Map<String, String> criteria);

    /**
     * Probability-weighted score over 2-10 ordered level descriptions. Empty when unavailable or on
     * failure.
     */
    Optional<Score> score(String state, String instructions, List<String> levels);

    /** Fail-visible pass-through for the tenant playground - empty when no key resolves at all. */
    Optional<PlaygroundOutcome> playground(String state, Map<String, JsonNode> questions);

    /**
     * Where the resolved API key came from - surfaced so status endpoints can tell a tenant whose
     * key is in play.
     */
    enum KeySource {
        /** The tenant's own BYOK row ({@code provider_credential}) - wins whenever present. */
        TENANT,
        /** The platform-wide tool credential an admin set in web-admin. */
        PLATFORM,
        /** The provider's env/property bootstrap key. */
        ENV
    }

    /**
     * A {@code choice} answer: winning criteria key, the full probability map, and the model's
     * confidence in the pick.
     */
    record Choice(String choice, Map<String, Double> probabilities, Double confidence) {}

    /**
     * A {@code score} answer: probability-weighted score over the ordered levels, the level
     * distribution, confidence.
     */
    record Score(double score, Map<String, Double> probabilities, Double confidence) {}

    /**
     * What the playground reports back: raw answers + usage on success, or the upstream failure
     * detail on error - never the key itself.
     */
    record PlaygroundOutcome(
            JsonNode answers,
            JsonNode usage,
            String model,
            KeySource keySource,
            Integer errorStatus,
            String error) {}
}
