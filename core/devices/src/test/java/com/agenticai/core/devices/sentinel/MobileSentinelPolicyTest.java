package com.agenticai.core.devices.sentinel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The tenant-wide sentinel policy is a single row pinned to {@link
 * MobileSentinelPolicy#SINGLETON_ID}; {@link MobileSentinelPolicy#update} rewrites it in place and
 * records who changed it and when.
 */
class MobileSentinelPolicyTest {

    private static MobileSentinelPolicy policy() {
        return new MobileSentinelPolicy(true, true, false, "HEART_RATE,SLEEP", true, 30);
    }

    @Test
    void aNewPolicyIsPinnedToTheSingletonRow() {
        MobileSentinelPolicy policy = policy();

        assertThat(policy.getId()).isEqualTo(MobileSentinelPolicy.SINGLETON_ID);
        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.isSyncEnabled()).isTrue();
        assertThat(policy.isAllowRawReadings()).isFalse();
        assertThat(policy.getAllowedKinds()).isEqualTo("HEART_RATE,SLEEP");
        assertThat(policy.isCaregiverEscalationEnabled()).isTrue();
        assertThat(policy.getRetentionDays()).isEqualTo(30);
        // No update yet - nothing to attribute.
        assertThat(policy.getUpdatedBy()).isNull();
        assertThat(policy.getUpdatedAt()).isNull();
    }

    @Test
    void updateRewritesEveryFieldAndStampsTheEditor() {
        MobileSentinelPolicy policy = policy();

        policy.update(false, false, true, "SLEEP", false, 7, "admin@acme");

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.isSyncEnabled()).isFalse();
        assertThat(policy.isAllowRawReadings()).isTrue();
        assertThat(policy.getAllowedKinds()).isEqualTo("SLEEP");
        assertThat(policy.isCaregiverEscalationEnabled()).isFalse();
        assertThat(policy.getRetentionDays()).isEqualTo(7);
        assertThat(policy.getUpdatedBy()).isEqualTo("admin@acme");
        assertThat(policy.getUpdatedAt()).isNotNull();
        // Still the same singleton row.
        assertThat(policy.getId()).isEqualTo(MobileSentinelPolicy.SINGLETON_ID);
    }
}
