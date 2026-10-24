package com.agenticai.core.agents.job.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a parked agent job reads from the settings catalog about carrying an approval decision into the resumed turn:
 * {@code approvals.continuation.enabled} and {@code approvals.continuation.jobsContinueAfterDenial}, resolved for the job's
 * agent (agent override, then workspace override, then the platform value from {@link ApprovalContinuationProperties}).
 *
 * <p>With no resolver (a slim context, a unit test) the platform properties answer, as before the catalog existed. If the
 * resolver fails, the platform properties answer too and a warning is logged: a settings outage must not change what a
 * parked job does.
 */
final class ApprovalContinuationSettings {

    private static final Logger log = LoggerFactory.getLogger(ApprovalContinuationSettings.class);

    static final String ENABLED = "approvals.continuation.enabled";
    static final String JOBS_CONTINUE_AFTER_DENIAL = "approvals.continuation.jobsContinueAfterDenial";

    private ApprovalContinuationSettings() {}

    /** Whether a decided approval is carried into the resumed turn of a job of this agent. */
    static boolean enabled(ApprovalContinuationProperties platform, SettingsResolver settings, UUID agentId) {
        return read(ENABLED, platform != null && platform.isEnabled(), settings, agentId);
    }

    /** Whether a denial lets the job carry on (needs {@link #enabled} too); off, a denial fails the job. */
    static boolean jobsContinueAfterDenial(ApprovalContinuationProperties platform, SettingsResolver settings, UUID agentId) {
        return read(JOBS_CONTINUE_AFTER_DENIAL, platform != null && platform.isJobsContinueAfterDenial(), settings, agentId);
    }

    private static boolean read(String key, boolean platformValue, SettingsResolver settings, UUID agentId) {
        if (settings == null) {
            return platformValue;
        }
        try {
            return settings.getBool(key, SettingContext.forAgent(agentId));
        } catch (RuntimeException e) {
            log.warn("Could not read setting {} for agent {}; using the platform value {}: {}", key, agentId, platformValue, e.toString());
            return platformValue;
        }
    }
}
