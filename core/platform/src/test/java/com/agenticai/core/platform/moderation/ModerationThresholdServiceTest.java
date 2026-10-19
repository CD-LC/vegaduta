package com.agenticai.core.platform.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.guardrails.moderation.ModerationPolicyPack;
import com.agenticai.core.guardrails.moderation.ModerationPolicyPackNotAvailableException;
import com.agenticai.core.guardrails.moderation.ModerationPolicyService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The batch-validation and partial-update semantics of the unified thresholds
 * endpoint (2026-08 moderation unification: this service now fronts the live
 * engine's own per-pack keys via {@link ModerationPolicyService} instead of
 * writing orphan platform-wide scalars nothing read).
 *
 * <p>Two contracts here are safety-relevant enough to pin. First, {@code
 * update()} validates the WHOLE batch before its first write - the
 * service-level check alone would reject mid-batch, leaving earlier packs
 * applied and the operator believing the rest landed too. Second, null (or an
 * omitted pack) means "leave unchanged" and only supplied fields may be
 * written: echoing stale fields back is how a concurrent admin edit gets
 * silently reverted (both contracts stated in the method's own javadoc).
 * The legal-review gate also lives on this path: tuning a pack that can never
 * run must 409 exactly like trying to enable it, or the admin surface is back
 * to writing config nothing reads - the defect the unification removed.
 *
 * <p>Pure logic + interaction verification on a mocked {@link
 * ModerationPolicyService} - no Spring context, no infrastructure, same
 * posture as the other unit tests in this package.
 */
class ModerationThresholdServiceTest {

    private ModerationPolicyService policy;
    private ModerationThresholdService service;

    @BeforeEach
    void setUp() {
        policy = mock(ModerationPolicyService.class);
        // Baseline mirrors ModerationPolicyService's hardcoded defaults for a
        // pack with no explicit rows: semantic off, review 50, block 90.
        // Individual tests override specific packs.
        when(policy.tuning(any(ModerationPolicyPack.class)))
            .thenReturn(new ModerationPolicyService.PackTuning(false, 50, 90));
        service = new ModerationThresholdService(policy);
    }

    // --- current(): catalogue shape ------------------------------------------------------------

    @Test
    void currentOmitsLegallyGatedPacksAndKeysTheRestByConfigKey() {
        ModerationThresholdService.Thresholds current = service.current();

        // CSAM is catalogue-only: resolveEnabled force-reads it disabled, so
        // offering its thresholds would invite writing config nothing reads.
        assertFalse(current.packs().containsKey(ModerationPolicyPack.CSAM.configKey()));
        // Spot-check both enforcement families are present under configKey.
        assertTrue(current.packs().containsKey(ModerationPolicyPack.FOUL_LANGUAGE.configKey()));
        assertTrue(current.packs().containsKey(ModerationPolicyPack.ILLEGAL.configKey()));
    }

    @Test
    void currentReflectsTheEnginesOwnTuningNotACopy() {
        when(policy.tuning(ModerationPolicyPack.PII))
            .thenReturn(new ModerationPolicyService.PackTuning(true, 30, 70));

        ModerationThresholdService.PackThresholds pii =
            service.current().packs().get(ModerationPolicyPack.PII.configKey());

        assertTrue(pii.semanticEnabled());
        assertEquals(30, pii.reviewThreshold());
        assertEquals(70, pii.blockThreshold());
    }

    // --- update(): validate-everything-before-writing-anything ---------------------------------

    @Test
    void anUnknownPackRejectsTheWholeBatchBeforeAnyWrite() {
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = new LinkedHashMap<>();
        packs.put(ModerationPolicyPack.FOUL_LANGUAGE.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, 40, 80));
        packs.put("not_a_pack",
            new ModerationThresholdService.PackThresholdsUpdate(null, 10, 20));

        assertThrows(IllegalArgumentException.class,
            () -> service.update(null, null, packs, "admin@test"));

        // The valid first entry must NOT have been applied: partial apply on a
        // safety surface is the failure mode this contract exists to prevent.
        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
    }

    @Test
    void aLegallyGatedPackRejectsTheWholeBatchWithTheSame409AsEnablingIt() {
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = new LinkedHashMap<>();
        packs.put(ModerationPolicyPack.FOUL_LANGUAGE.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, 40, 80));
        packs.put(ModerationPolicyPack.CSAM.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, 10, 20));

        assertThrows(ModerationPolicyPackNotAvailableException.class,
            () -> service.update(null, null, packs, "admin@test"));

        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
        verify(policy, never()).setSemanticEnabled(any(), anyBoolean(), anyString());
    }

    @Test
    void anInvertedBandAfterMergingWithCurrentValuesIsRejectedBeforeAnyWrite() {
        // Only reviewThreshold supplied; current block is the 90 default. 95
        // would invert the band ONLY once merged with the unsupplied half -
        // the check must merge, not just validate supplied fields in isolation.
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = Map.of(
            ModerationPolicyPack.HARASSMENT.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, 95, null));

        assertThrows(IllegalArgumentException.class,
            () -> service.update(null, null, packs, "admin@test"));

        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
    }

    @Test
    void anOutOfRangeValueIsRejected() {
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = Map.of(
            ModerationPolicyPack.PII.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, -1, null));

        assertThrows(IllegalArgumentException.class,
            () -> service.update(null, null, packs, "admin@test"));

        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
    }

    // --- update(): null means "leave unchanged" ------------------------------------------------

    @Test
    void onlySuppliedFieldsAreWritten() {
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = Map.of(
            ModerationPolicyPack.EXTREMISM.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(true, null, null));

        service.update(null, null, packs, "admin@test");

        // Semantic flag was supplied -> written; thresholds were not -> the
        // threshold write path must not run at all (writing merged echoes is
        // how a concurrent edit gets reverted).
        verify(policy).setSemanticEnabled(ModerationPolicyPack.EXTREMISM, true, "admin@test");
        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
        // Neither master switch was supplied -> neither may be touched.
        verify(policy, never()).setMasterEnabled(anyBoolean(), anyString());
        verify(policy, never()).setExternalClassifierEnabled(anyBoolean(), anyString());
    }

    @Test
    void suppliedThresholdsArePassedThroughWithTheActor() {
        Map<String, ModerationThresholdService.PackThresholdsUpdate> packs = Map.of(
            ModerationPolicyPack.FOUL_LANGUAGE.configKey(),
            new ModerationThresholdService.PackThresholdsUpdate(null, 40, 80));

        service.update(null, null, packs, "admin@test");

        verify(policy).setThresholds(ModerationPolicyPack.FOUL_LANGUAGE, 40, 80, "admin@test");
        verify(policy, never()).setSemanticEnabled(any(), anyBoolean(), anyString());
    }

    @Test
    void masterSwitchesWriteOnlyWhenSupplied() {
        service.update(true, null, Map.of(), "admin@test");

        verify(policy).setMasterEnabled(true, "admin@test");
        verify(policy, never()).setExternalClassifierEnabled(anyBoolean(), anyString());
        verify(policy, never()).setThresholds(any(), any(), any(), anyString());
    }

    @Test
    void updateReturnsTheFreshCatalogueAfterApplying() {
        when(policy.isModerationEnabled()).thenReturn(true);

        ModerationThresholdService.Thresholds after =
            service.update(true, null, Map.of(), "admin@test");

        assertTrue(after.moderationEnabled());
        assertEquals(anyTunablePackCount(), after.packs().size());
    }

    private static int anyTunablePackCount() {
        int count = 0;
        for (ModerationPolicyPack pack : ModerationPolicyPack.values()) {
            if (!pack.requiresLegalReview()) {
                count++;
            }
        }
        return count;
    }
}
