package com.agenticai.core.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One entry of {@code GET /api/settings/catalog}. {@code default} is the platform value in force (from the same
 * property object the runtime reads). Additive fields beyond the contract: {@code strictness} (security keys),
 * {@code platformProperty} (the yml key an operator sets).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogEntry(
        String key,
        String group,
        String labelKey,
        String descriptionKey,
        String helpKey,
        SettingType type,
        SettingUnit unit,
        Number min,
        Number max,
        Number step,
        List<String> enumValues,
        SettingRisk risk,
        List<SettingScope> scopes,
        @JsonProperty("default") Object defaultValue,
        SettingRole minRole,
        boolean restartRequired,
        String docsAnchor,
        String strictness,
        String platformProperty) {

    static CatalogEntry of(SettingDefinition d) {
        return new CatalogEntry(
                d.key(),
                d.group(),
                d.labelKey(),
                d.descriptionKey(),
                d.helpKey(),
                d.type(),
                d.unit(),
                d.num(d.min()),
                d.num(d.max()),
                d.num(d.step()),
                d.enumValues(),
                d.risk(),
                d.scopes(),
                d.platformValue(),
                d.minRole(),
                false,
                d.docsAnchor(),
                d.strictness() == Strictness.NONE ? null : d.strictness().name().toLowerCase(java.util.Locale.ROOT),
                d.platformProperty());
    }
}
