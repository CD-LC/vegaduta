package com.agenticai.core.prompts;

import java.util.Locale;
import java.util.Optional;

/**
 * Where an override applies. Resolution order, most specific first: {@link #AGENT}, {@link #TENANT},
 * {@link #PLATFORM}, then the classpath default.
 */
public enum PromptScope {
    /** One agent of the current tenant ({@code scope_id} = the agent's UUID). */
    AGENT,
    /** Every agent of the current tenant ({@code scope_id} is empty: the schema is the tenant). */
    TENANT,
    /** Every tenant of the install (stored in the control plane; {@code scope_id} is empty). */
    PLATFORM;

    /** The value stored in the {@code scope} column and used in URLs. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PromptScope> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PromptScope s : values()) {
            if (s.dbValue().equalsIgnoreCase(value.trim())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
