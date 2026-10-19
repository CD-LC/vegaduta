package com.agenticai.core.guardrails.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the properties of the pack catalogue that are safety decisions
 * rather than implementation details, so that a later refactor has to break a
 * test to change them. Moved from core/platform's test tree when the two
 * moderation subsystems were unified (2026-08) and retargeted at the merged
 * enum, plus three merge-specific guards: the legacy-id aliases, the
 * configKey stability of the original six packs, and the
 * no-patterns-for-classifier-packs invariant.
 */
class ModerationPolicyPackCatalogueTest {

    @Test
    @DisplayName("CSAM is held for legal review and is the only pack that is")
    void csamIsHeld() {
        assertTrue(ModerationPolicyPack.CSAM.requiresLegalReview(),
            "CSAM must stay gated until the mandatory-reporting flow and restricted evidence trail "
                + "are designed with counsel - see web-admin safety page.");

        long held = Arrays.stream(ModerationPolicyPack.values())
            .filter(ModerationPolicyPack::requiresLegalReview)
            .count();
        assertEquals(1, held, "If another pack becomes legally gated, that is a deliberate decision - "
            + "update this test consciously rather than letting it drift.");
    }

    @Test
    @DisplayName("a held pack never defaults to enabled")
    void heldPackNotDefaultOn() {
        Arrays.stream(ModerationPolicyPack.values())
            .filter(ModerationPolicyPack::requiresLegalReview)
            .forEach(p -> assertFalse(p.defaultEnabled(),
                p.configKey() + " is gated for legal review, so it must not be on by default"));
    }

    @Test
    @DisplayName("the high-stakes categories are classifier-backed, never keyword lists")
    void highStakesAreClassifierBacked() {
        // Authoring keyword lists for these categories would be trivially
        // bypassed and would fire hardest on journalism, research and
        // counter-speech. They route to the semantic judge and external
        // classifier instead - and this repo therefore contains no such terms.
        assertEquals(ModerationPolicyPack.Enforcement.CLASSIFIER, ModerationPolicyPack.CSAM.enforcement());
        assertEquals(ModerationPolicyPack.Enforcement.CLASSIFIER, ModerationPolicyPack.ILLEGAL.enforcement());
        // The retired platform catalogue also had a classifier-only TERRORISM
        // entry. The merge deliberately folded it into EXTREMISM, which IS
        // pattern-backed - but scoped to hate/extremist RHETORIC only, never
        // credible-threat detection (that is the ExternalSafetyClassifier
        // seam's job, on the restricted audit path). Labelling EXTREMISM
        // CLASSIFIER would be false while its rhetoric rules exist and fire.
        // This assertion makes that taxonomy decision visible; changing it is
        // a safety-copy decision, not a refactor.
        assertEquals(ModerationPolicyPack.Enforcement.DETERMINISTIC_PATTERN,
            ModerationPolicyPack.EXTREMISM.enforcement());
    }

    @Test
    @DisplayName("pack ids round-trip, so a stored row always resolves back")
    void idsRoundTrip() {
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            assertEquals(pack, ModerationPolicyPack.fromConfigKey(pack.configKey()).orElseThrow());
            assertEquals(pack, ModerationPolicyPack.fromConfigKey(pack.configKey().toUpperCase(Locale.ROOT)).orElseThrow());
        }
        assertTrue(ModerationPolicyPack.fromConfigKey("nope").isEmpty());
        assertTrue(ModerationPolicyPack.fromConfigKey(null).isEmpty());
    }

    @Test
    @DisplayName("legacy platform-catalogue ids resolve as read-side aliases")
    void legacyIdsResolve() {
        // The retired core/platform catalogue named three categories
        // differently. Rows V216 carried over from its table, and any operator
        // script written against the old API, use those ids - they must keep
        // resolving. Read-side ONLY: configKey() stays the storage token.
        assertEquals(ModerationPolicyPack.FOUL_LANGUAGE, ModerationPolicyPack.fromConfigKey("profanity").orElseThrow());
        assertEquals(ModerationPolicyPack.RESTRICTED_CONTENT, ModerationPolicyPack.fromConfigKey("restricted").orElseThrow());
        assertEquals(ModerationPolicyPack.EXTREMISM, ModerationPolicyPack.fromConfigKey("terrorism").orElseThrow());
    }

    @Test
    @DisplayName("the original six configKeys never change - they live in tenant review rows")
    void originalConfigKeysAreStable() {
        // These tokens are persisted in platform_setting keys AND in every
        // tenant schema's moderation_review_item.policy_pack column (tenant
        // V121). Renaming one silently orphans stored config and reinterprets
        // audit history - which is exactly what configKey() exists to prevent.
        assertEquals("foul_language", ModerationPolicyPack.FOUL_LANGUAGE.configKey());
        assertEquals("restricted_content", ModerationPolicyPack.RESTRICTED_CONTENT.configKey());
        assertEquals("pii", ModerationPolicyPack.PII.configKey());
        assertEquals("harassment", ModerationPolicyPack.HARASSMENT.configKey());
        assertEquals("extremism", ModerationPolicyPack.EXTREMISM.configKey());
        assertEquals("self_harm", ModerationPolicyPack.SELF_HARM.configKey());
    }

    @Test
    @DisplayName("no CLASSIFIER pack has pattern rules - their absence is load-bearing")
    @SuppressWarnings("unchecked")
    void classifierPacksHaveNoPatternRules() throws Exception {
        // Reaches into PolicyPackPatternMatcher.RULES reflectively because the
        // map is (rightly) private - the invariant is about what this repo
        // refuses to author, and a public accessor just to test it would
        // invite callers.
        Field rulesField = PolicyPackPatternMatcher.class.getDeclaredField("RULES");
        rulesField.setAccessible(true);
        Map<ModerationPolicyPack, List<?>> rules = (Map<ModerationPolicyPack, List<?>>) rulesField.get(null);

        Set<ModerationPolicyPack> withRules = rules.keySet();
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            if (pack.enforcement() == ModerationPolicyPack.Enforcement.CLASSIFIER) {
                assertFalse(withRules.contains(pack),
                    pack.configKey() + " is CLASSIFIER-enforced: authoring keyword lists for it is "
                        + "the thing the catalogue design refuses to do (see PolicyPackPatternMatcher.RULES)");
            }
        }
    }
}
