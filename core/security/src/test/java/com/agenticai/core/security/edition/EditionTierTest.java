package com.agenticai.core.security.edition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link EditionTier#of} parsing rules: absent/blank/unrecognised all mean {@link
 * EditionTier#FULL} (an install that never heard of editions keeps every feature), while a real
 * value is trimmed and case-insensitive.
 */
class EditionTierTest {

    private static MockEnvironment env(String tier) {
        return tier == null ? new MockEnvironment() : new MockEnvironment().withProperty(EditionTier.PROPERTY, tier);
    }

    @Test
    void absentBlankAndUnrecognisedAllMeanFull() {
        assertThat(EditionTier.of(env(null))).isEqualTo(EditionTier.FULL);
        assertThat(EditionTier.of(env(""))).isEqualTo(EditionTier.FULL);
        assertThat(EditionTier.of(env("   "))).isEqualTo(EditionTier.FULL);
        assertThat(EditionTier.of(env("enterprise"))).isEqualTo(EditionTier.FULL);
    }

    @Test
    void knownValuesAreParsedCaseInsensitivelyWithWhitespaceTrimmed() {
        assertThat(EditionTier.of(env("personal"))).isEqualTo(EditionTier.PERSONAL);
        assertThat(EditionTier.of(env(" PERSONAL "))).isEqualTo(EditionTier.PERSONAL);
        assertThat(EditionTier.of(env("Lite"))).isEqualTo(EditionTier.LITE);
        assertThat(EditionTier.of(env("full"))).isEqualTo(EditionTier.FULL);
    }

    @Test
    void idIsTheLowercaseApiForm() {
        assertThat(EditionTier.PERSONAL.id()).isEqualTo("personal");
        assertThat(EditionTier.LITE.id()).isEqualTo("lite");
        assertThat(EditionTier.FULL.id()).isEqualTo("full");
    }
}
