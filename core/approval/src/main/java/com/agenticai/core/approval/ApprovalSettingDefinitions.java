package com.agenticai.core.approval;

import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;

/**
 * The settings-catalog entry for how long a request may wait for a person, declared next to the job that reads it
 * ({@link ApprovalExpiryJob}). The platform value is {@code app.approvals.expiry.ttl} from {@link ApprovalExpiryProperties}.
 * Registered through {@code BehaviourSettingsCatalog} (agents-turn).
 */
public final class ApprovalSettingDefinitions {

    /** How long a request may stay pending before it expires; 0 = never. A workspace setting (an approval has no single agent). */
    public static final String EXPIRY_TTL = "approvals.expiry.ttl";

    private ApprovalSettingDefinitions() {}

    public static SettingDefinition expiryTtl(ApprovalExpiryProperties expiry) {
        return SettingDefinition.builder(EXPIRY_TTL, "approvals", SettingType.DURATION)
                .unit(SettingUnit.S).range(0, 30 * 86_400).risk(SettingRisk.HIGH).scopes(SettingScope.TENANT)
                .property("app.approvals.expiry.ttl").docsAnchor("behaviour-settings")
                .platform(expiry::getTtl).build();
    }
}
