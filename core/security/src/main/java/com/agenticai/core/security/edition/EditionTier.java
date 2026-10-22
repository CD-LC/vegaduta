package com.agenticai.core.security.edition;

import java.util.Locale;
import org.springframework.core.env.PropertyResolver;

/**
 * The edition tier an install runs as ({@value #PROPERTY}): one switch that decides the default of every
 * {@link EditionFeature}.
 *
 * <ul>
 *   <li>{@link #PERSONAL}: one person, one laptop. Features that only make sense for an operator or a
 *       multi-tenant business default to OFF.
 *   <li>{@link #LITE} and {@link #FULL}: every feature is ON, exactly today's behaviour.
 * </ul>
 *
 * Absent, blank or unrecognised means {@link #FULL}: an install that never heard of editions (every
 * existing profile, shape and test) keeps every feature.
 */
public enum EditionTier {
    PERSONAL,
    LITE,
    FULL;

    /** The single tier property. The {@code personal} profile defaults it to {@code personal}. */
    public static final String PROPERTY = "app.edition.tier";

    /** Lower-case form used in the API and in the web build ({@code personal | lite | full}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reads {@value #PROPERTY}; trims and ignores case; anything unrecognised is {@link #FULL}. */
    public static EditionTier of(PropertyResolver environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (EditionTier tier : values()) {
            if (tier.name().equals(normalised)) {
                return tier;
            }
        }
        return FULL;
    }
}
