package com.agenticai.core.guardrails;

/**
 * Enum of available content-filtering guardrails. Each type corresponds to a
 * specific implementation that can detect different categories of harmful or
 * policy-violating content.
 *
 * <p><b>Tier availability:</b>
 * <ul>
 *   <li>FREE: PROFANITY, JAILBREAK (basic safety)</li>
 *   <li>PREMIUM: + PII, POLICY_VIOLATION (extended safety)</li>
 *   <li>UNLIMITED: + TOXICITY, CUSTOM (full suite)</li>
 * </ul>
 *
 * <p>Each guardrail operates independently on both input (user message) and
 * output (agent response) paths, returning a {@link ContentGuardrailResult}
 * indicating pass/fail and a reason if blocked.
 */
public enum ContentGuardrailType {
    /**
     * Detects and filters profanity and offensive language. Basic, regex-based
     * approach suitable for all tiers.
     */
    PROFANITY("profanity", "Profanity Filter", true),

    /**
     * Detects prompt injection and jailbreak attempts in user input. Pattern-
     * based detection of common injection techniques.
     */
    JAILBREAK("jailbreak", "Jailbreak Detector", true),

    /**
     * Detects personally identifiable information (emails, phone numbers, SSNs,
     * credit cards) in responses. Prevents data leakage from agent outputs.
     */
    PII("pii", "PII Detector", false),

    /**
     * Detects content violating platform policies (e.g., violence, illegal
     * activities, hate speech). Pattern and semantic-based.
     */
    POLICY_VIOLATION("policy_violation", "Policy Violation Detector", false),

    /**
     * Detects toxic language and hostile tone using semantic analysis.
     * Requires model integration.
     */
    TOXICITY("toxicity", "Toxicity Detector", false),

    /**
     * Placeholder for custom guardrails - tenant-specific rules.
     * Premium+ only.
     */
    CUSTOM("custom", "Custom Guardrail", false);

    private final String id;
    private final String displayName;
    private final boolean freeTierAvailable;

    ContentGuardrailType(String id, String displayName, boolean freeTierAvailable) {
        this.id = id;
        this.displayName = displayName;
        this.freeTierAvailable = freeTierAvailable;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isFreeTierAvailable() {
        return freeTierAvailable;
    }

    /**
     * Resolve a guardrail type by its ID string.
     *
     * @param id the guardrail ID (e.g., "profanity", "jailbreak")
     * @return the corresponding enum value
     * @throws IllegalArgumentException if ID is not recognized
     */
    public static ContentGuardrailType fromId(String id) {
        for (ContentGuardrailType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown guardrail type: " + id);
    }
}
