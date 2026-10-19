package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.common.privacy.PrivacyDataClass;
import com.agenticai.core.common.privacy.RetentionPolicyService;
import com.agenticai.core.common.privacy.RetentionPurger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/** DPDP retention: per-tenant, per-channel purge of expired data, audited. See {@link DpdpControlsFixture}. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class DpdpRetentionIT extends DpdpControlsFixture {

    @BeforeAll
    static void migrate() {
        migrateFreshDatabase("dpdp_retention_it");
    }

    // ------------------------------------------------------------------------- retention

    @Test
    void purgeRemovesOnlyExpiredDataOfTheTenantItRunsInAndAuditsIt() {
        Instant old = Instant.now().minus(Duration.ofDays(400));
        inTenant(A, () -> {
            retention.put("*", PrivacyDataClass.TRANSCRIPT, true, 365, "admin");
            retention.put("*", PrivacyDataClass.EXTRACTED_FIELDS, true, 365, "admin");
            retention.put("VOICE", PrivacyDataClass.RECORDING, true, 365, "admin");
            seedOldData(agentA, old);
        });
        // Tenant B holds identical expired data but has NOT enabled any rule.
        inTenant(B, () -> seedOldData(agentB, old));

        inTenant(A, () -> {
            List<RetentionPurger.Result> results = purger.purgeCurrentTenant();
            assertThat(results).extracting(RetentionPurger.Result::dataClass)
                    .containsExactlyInAnyOrder(PrivacyDataClass.TRANSCRIPT, PrivacyDataClass.RECORDING, PrivacyDataClass.EXTRACTED_FIELDS);

            // Old rows gone, recent rows kept.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log WHERE created_at < now() - interval '365 days'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_memory", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM voice_call_recording", Integer.class)).isEqualTo(1);
            verify(storage).delete("recordings/old.ogg");
            verify(storage, never()).delete("recordings/new.ogg");
        });
        inTenant(B, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_memory", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM voice_call_recording", Integer.class)).isEqualTo(2);
        });
        // One audit event per purge that removed something, from the system actor.
        verify(audit, times(3)).record(eq("system:retention-purge"), eq("privacy.retention.purge"), anyString(), anyString(), eq(AuditOutcome.SUCCESS), anyString());
    }

    @Test
    void runningTheSamePurgeUnderTheOtherTenantStillLeavesTheFirstTenantsRetainedDataAlone() {
        Instant old = Instant.now().minus(Duration.ofDays(400));
        inTenant(A, () -> seedOldData(agentA, old));
        inTenant(B, () -> {
            retention.put("*", PrivacyDataClass.TRANSCRIPT, true, 365, "admin");
            seedOldData(agentB, old);
            purger.purgeCurrentTenant();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log", Integer.class)).isEqualTo(2);
        });
        inTenant(A, () -> assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log", Integer.class)).isEqualTo(4));
    }

    @Test
    void nothingIsPurgedWithoutAnEnabledRule() {
        inTenant(A, () -> {
            seedOldData(agentA, Instant.now().minus(Duration.ofDays(900)));
            retention.put("*", PrivacyDataClass.TRANSCRIPT, false, 30, "admin");
            assertThat(purger.purgeCurrentTenant()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_message_log", Integer.class)).isEqualTo(4);
            verify(audit, never()).record(eq("system:retention-purge"), anyString(), anyString(), anyString(), any(), anyString());
        });
    }

    @Test
    void channelRuleOverridesTheTenantDefaultIncludingBeingSwitchedOff() {
        inTenant(A, () -> {
            retention.put("*", PrivacyDataClass.TRANSCRIPT, true, 30, "admin");
            retention.put(CHANNEL, PrivacyDataClass.TRANSCRIPT, true, 500, "admin");
            assertThat(retention.effective(CHANNEL, PrivacyDataClass.TRANSCRIPT).orElseThrow().retentionDays()).isEqualTo(500);
            assertThat(retention.effective("TELEGRAM", PrivacyDataClass.TRANSCRIPT).orElseThrow().retentionDays()).isEqualTo(30);

            retention.put(CHANNEL, PrivacyDataClass.TRANSCRIPT, false, 500, "admin");
            assertThat(retention.effective(CHANNEL, PrivacyDataClass.TRANSCRIPT)).isEmpty();
        });
    }

    @Test
    void aRecordingWhoseObjectCannotBeDeletedIsKeptForRetryAndReportedAsFailed() {
        inTenant(A, () -> {
            retention.put("VOICE", PrivacyDataClass.RECORDING, true, 365, "admin");
            seedOldData(agentA, Instant.now().minus(Duration.ofDays(400)));
            doThrow(new IllegalStateException("s3 down")).when(storage).delete("recordings/old.ogg");

            List<RetentionPurger.Result> results = purger.purgeCurrentTenant();

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.deleted()).isZero();
                assertThat(r.failed()).isEqualTo(1);
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM voice_call_recording", Integer.class)).isEqualTo(2);
            verify(audit).record(eq("system:retention-purge"), eq("privacy.retention.purge"), anyString(), anyString(), eq(AuditOutcome.FAILURE), anyString());
        });
    }

    @Test
    void retentionDefaultsAreTheOneYearFloorAndShorterPeriodsCarryAWarning() {
        inTenant(A, () -> {
            RetentionPolicyService.Saved defaulted = retention.put("*", PrivacyDataClass.TRANSCRIPT, true, null, "admin");
            assertThat(defaulted.rule().retentionDays()).isEqualTo(365);
            assertThat(defaulted.warnings()).noneMatch(w -> w.contains("Shorter than 365"));

            RetentionPolicyService.Saved shorter = retention.put("*", PrivacyDataClass.TRANSCRIPT, true, 90, "admin");
            assertThat(shorter.warnings()).anyMatch(w -> w.contains("Shorter than 365") && w.contains("counsel"));

            assertThatThrownBy(() -> retention.put("TELEGRAM", PrivacyDataClass.RECORDING, true, 10, "admin"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> retention.put("*", PrivacyDataClass.TRANSCRIPT, true, 0, "admin"))
                    .isInstanceOf(ResponseStatusException.class);
        });
    }
}
