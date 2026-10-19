package com.agenticai.core.guardrails.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSetting;
import com.agenticai.core.tenancy.PlatformSettingRepository;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase P6. Exercises the config-resolution logic against a REAL {@link
 * PlatformSettingsService} backed by an in-memory settings map - so the
 * default-off posture, most-specific-wins enablement, and threshold parsing
 * are proven through the actual runtime code path, not a re-mock of it.
 */
class ModerationPolicyServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private ModerationPolicyService policy;

    @BeforeEach
    void setUp() {
        PlatformSettingRepository repo = mock(PlatformSettingRepository.class);
        when(repo.findById(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = store.get(key);
            return value == null ? Optional.empty() : Optional.of(new PlatformSetting(key, value, null));
        });
        // save() writes back into the same map, so the write-path tests below
        // prove the round trip through the REAL PlatformSettingsService - a
        // key setPackEnabled writes is a key resolve() then reads, which is
        // the whole point of the unified read/write service.
        when(repo.save(any(PlatformSetting.class))).thenAnswer(inv -> {
            PlatformSetting setting = inv.getArgument(0);
            store.put(setting.getKey(), setting.getValue());
            return setting;
        });
        policy = new ModerationPolicyService(new PlatformSettingsService(repo));
    }

    private ModerationContext ctx(String tenantId, String plan) {
        return new ModerationContext(tenantId, plan, UUID.randomUUID(), "sess", "actor", "user_input");
    }

    @Test
    void everythingIsOffByDefault() {
        assertThat(policy.isModerationEnabled()).isFalse();
        assertThat(policy.isExternalClassifierEnabled()).isFalse();
        assertThat(policy.enabledPacks(ctx("t1", "PRO"))).isEmpty();
    }

    @Test
    void masterSwitchAndExternalFlagReadFromSettings() {
        store.put("moderation.enabled", "true");
        // Canonical boolean literal: PlatformSettingsService.getBoolean deliberately
        // accepts only "true"/"false" (a typo can't silently flip a safety flag);
        // the seeded settings use these literals.
        store.put("moderation.external-classifier.enabled", "true");
        assertThat(policy.isModerationEnabled()).isTrue();
        assertThat(policy.isExternalClassifierEnabled()).isTrue();
    }

    @Test
    void globalPackDefaultEnablesForEveryTenant() {
        store.put("moderation.pack.pii.enabled", "true");
        assertThat(policy.enabledPacks(ctx("t1", "PRO"))).contains(ModerationPolicyPack.PII);
        assertThat(policy.enabledPacks(ctx("t2", "FREE"))).contains(ModerationPolicyPack.PII);
    }

    @Test
    void tenantOverrideBeatsPlanBeatsGlobal() {
        // Global default: pii OFF.
        store.put("moderation.pack.pii.enabled", "false");
        // Plan PRO: ON.
        store.put("moderation.pack.pii.enabled.plan.PRO", "true");
        // Tenant t-optout: explicitly OFF even though plan says on.
        store.put("moderation.pack.pii.enabled.tenant.t-optout", "false");

        assertThat(policy.resolve(ModerationPolicyPack.PII, ctx("t-any", "PRO")).enabled())
            .as("plan override wins over global off").isTrue();
        assertThat(policy.resolve(ModerationPolicyPack.PII, ctx("t-optout", "PRO")).enabled())
            .as("tenant opt-out wins over plan on").isFalse();
        assertThat(policy.resolve(ModerationPolicyPack.PII, ctx("t-any", "FREE")).enabled())
            .as("no plan/tenant row -> global off").isFalse();
    }

    @Test
    void thresholdsAndSemanticFlagResolveWithDefaults() {
        store.put("moderation.pack.harassment.enabled", "true");
        PolicyPackConfig defaults = policy.resolve(ModerationPolicyPack.HARASSMENT, ctx("t1", "PRO"));
        assertThat(defaults.reviewThreshold()).isEqualTo(50);
        assertThat(defaults.blockThreshold()).isEqualTo(90);
        assertThat(defaults.semanticEnabled()).isFalse();

        store.put("moderation.pack.harassment.semantic.enabled", "true");
        store.put("moderation.pack.harassment.threshold.review", "30");
        store.put("moderation.pack.harassment.threshold.block", "80");
        PolicyPackConfig tuned = policy.resolve(ModerationPolicyPack.HARASSMENT, ctx("t1", "PRO"));
        assertThat(tuned.semanticEnabled()).isTrue();
        assertThat(tuned.reviewThreshold()).isEqualTo(30);
        assertThat(tuned.blockThreshold()).isEqualTo(80);
    }

    @Test
    void outOfRangeThresholdFallsBackToDefault() {
        store.put("moderation.pack.foul_language.enabled", "true");
        store.put("moderation.pack.foul_language.threshold.block", "250"); // invalid
        PolicyPackConfig cfg = policy.resolve(ModerationPolicyPack.FOUL_LANGUAGE, ctx("t1", "PRO"));
        assertThat(cfg.blockThreshold()).isEqualTo(90);
    }

    @Test
    void disabledPackResolvesToDisabledConfig() {
        PolicyPackConfig cfg = policy.resolve(ModerationPolicyPack.SELF_HARM, ctx("t1", "PRO"));
        assertThat(cfg.enabled()).isFalse();
    }

    @Test
    void legallyGatedPackNeverEnablesEvenWithExplicitRows() {
        // The CSAM gate is not advisory: even a hand-written row at every
        // scope level must not put the pack into enabledPacks(), because that
        // is what keeps both the pattern and semantic layers from ever being
        // asked about it. Defence by design, not by the accident that no
        // rules exist for it.
        store.put("moderation.enabled", "true");
        store.put("moderation.pack.csam.enabled", "true");
        store.put("moderation.pack.csam.enabled.plan.PRO", "true");
        store.put("moderation.pack.csam.enabled.tenant.t1", "true");
        assertThat(policy.enabledPacks(ctx("t1", "PRO"))).doesNotContain(ModerationPolicyPack.CSAM);
        assertThat(policy.resolve(ModerationPolicyPack.CSAM, ctx("t1", "PRO")).enabled()).isFalse();
    }

    @Test
    void setPackEnabledRefusesLegallyGatedPack() {
        assertThatThrownBy(() -> policy.setPackEnabled(
            ModerationPolicyPack.CSAM, ModerationPolicyScope.GLOBAL, null, true, "admin"))
            .isInstanceOf(ModerationPolicyPackNotAvailableException.class);
        // And the refusal happened before any write.
        assertThat(store).doesNotContainKey("moderation.pack.csam.enabled");
    }

    @Test
    void writePathRoundTripsThroughTheKeysTheReadPathResolves() {
        // The regression this guards: an admin surface writing config the
        // engine never reads (the pre-unification core/platform defect).
        policy.setPackEnabled(ModerationPolicyPack.HARASSMENT, ModerationPolicyScope.GLOBAL, null, true, "admin");
        policy.setPackEnabled(ModerationPolicyPack.HARASSMENT, ModerationPolicyScope.TENANT, "t-optout", false, "admin");
        // Plan keys are normalised upper-case on write, matching how the plan
        // token reaches the resolver at runtime (TenantPlan.name()).
        policy.setPackEnabled(ModerationPolicyPack.PII, ModerationPolicyScope.PLAN, "pro", true, "admin");
        policy.setThresholds(ModerationPolicyPack.HARASSMENT, 30, 80, "admin");
        policy.setSemanticEnabled(ModerationPolicyPack.HARASSMENT, true, "admin");

        assertThat(policy.resolve(ModerationPolicyPack.HARASSMENT, ctx("t1", "PRO")).enabled()).isTrue();
        assertThat(policy.resolve(ModerationPolicyPack.HARASSMENT, ctx("t-optout", "PRO")).enabled())
            .as("tenant opt-out written by the admin path wins at resolve time").isFalse();
        assertThat(policy.resolve(ModerationPolicyPack.PII, ctx("t1", "PRO")).enabled()).isTrue();

        PolicyPackConfig tuned = policy.resolve(ModerationPolicyPack.HARASSMENT, ctx("t1", "PRO"));
        assertThat(tuned.semanticEnabled()).isTrue();
        assertThat(tuned.reviewThreshold()).isEqualTo(30);
        assertThat(tuned.blockThreshold()).isEqualTo(80);
    }

    @Test
    void setThresholdsRejectsAStateResolveWouldRewrite() {
        // resolve() normalises review<=block on read; the write path must not
        // be able to store the inverted state in the first place.
        assertThatThrownBy(() -> policy.setThresholds(ModerationPolicyPack.PII, 95, 60, "admin"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setThresholds(ModerationPolicyPack.PII, -1, null, "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
