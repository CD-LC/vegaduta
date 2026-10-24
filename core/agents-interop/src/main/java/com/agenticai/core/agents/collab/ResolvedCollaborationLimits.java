package com.agenticai.core.agents.collab;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fan-out bounds of one collaboration tool as the settings catalog resolves them for the calling agent: {@code
 * delegation.maxBranches} and {@code delegation.branchTimeoutSeconds} ({@link CollaborationSettings}), each the agent's own
 * value, else its workspace's, else the platform's {@code app.agents.collaboration.*}. The tools are built on the request
 * thread but their branches (and a verifier's repair round) run on other threads, so every read applies the {@code
 * TenantContext} captured at tool-resolution time for the duration of the lookup and restores the thread's own afterwards.
 * With no captured context the thread's tenant is used, and with none at all the platform value comes back.
 *
 * <p>With no resolver (a unit test, a context without the catalog) or on any settings failure the platform value is used
 * and a warning logged: a settings outage never stops a fan-out. The result is clamped to at least 1, as the property
 * setters do.
 */
final class ResolvedCollaborationLimits {

    private static final Logger log = LoggerFactory.getLogger(ResolvedCollaborationLimits.class);

    private final CollaborationProperties platform;
    private final SettingsResolver settings;
    private final UUID agentId;
    private final TenantContext tenant;

    /** @param agentId the agent whose turn runs the tool (null = workspace level); @param tenant the request thread's context, may be null */
    ResolvedCollaborationLimits(CollaborationProperties platform, SettingsResolver settings, UUID agentId, TenantContext tenant) {
        this.platform = platform;
        this.settings = settings;
        this.agentId = agentId;
        this.tenant = tenant;
    }

    /** Platform values only. */
    static ResolvedCollaborationLimits platformOnly(CollaborationProperties platform) {
        return new ResolvedCollaborationLimits(platform, null, null, null);
    }

    boolean usesSettings() {
        return settings != null;
    }

    int maxBranches() {
        return (int) Math.max(1, read(CollaborationSettings.MAX_BRANCHES, platform::getMaxBranches,
                r -> (long) r.getInt(CollaborationSettings.MAX_BRANCHES, SettingContext.forAgent(agentId))));
    }

    long branchTimeoutSeconds() {
        return Math.max(1, read(CollaborationSettings.BRANCH_TIMEOUT_SECONDS, platform::getBranchTimeoutSeconds,
                r -> r.getLong(CollaborationSettings.BRANCH_TIMEOUT_SECONDS, SettingContext.forAgent(agentId))));
    }

    private long read(String key, Supplier<? extends Number> platformValue, java.util.function.ToLongFunction<SettingsResolver> resolved) {
        if (settings == null) {
            return platformValue.get().longValue();
        }
        TenantContext previous = TenantContext.current().orElse(null);
        boolean swap = tenant != null && tenant != previous;
        if (swap) {
            TenantContext.set(tenant);
        }
        try {
            return resolved.applyAsLong(settings);
        } catch (RuntimeException e) {
            log.warn("Could not read setting {}; using the platform value: {}", key, e.toString());
            return platformValue.get().longValue();
        } finally {
            if (swap) {
                if (previous != null) {
                    TenantContext.set(previous);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }
}
