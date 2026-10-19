package com.agenticai.core.guardrails.moderation;

/**
 * One policy pack as the admin surface sees it: the catalogue metadata off the
 * enum plus the effective enablement at one requested scope, as resolved by
 * {@link ModerationPolicyService#catalogue}. Moved from the retired
 * platform-side {@code ModerationPolicyPackService.PackState} and extended
 * with the two fields the layered {@code platform_setting} store can answer
 * and the old single-table store could not: WHICH level actually decided the
 * value, and whether the requested scope has its own explicit row.
 *
 * <p>A pack held for legal review always reads {@code enabled=false}
 * regardless of any stored value - the UI must never show a pack as
 * protecting something it is not actually protecting.
 *
 * @param id the pack's {@link ModerationPolicyPack#configKey()} - the stable
 *     wire/storage token (NOT the legacy platform-catalogue id; those are
 *     accepted read-side only, see {@link ModerationPolicyPack#fromConfigKey})
 * @param displayName human label for the admin UI
 * @param description operator-facing description of the category
 * @param enforcement {@link ModerationPolicyPack.Enforcement} name - whether
 *     this repo authors pattern rules for the category or refuses to
 * @param enabled the value the ENGINE would resolve at the requested scope -
 *     always false for a legally-gated pack
 * @param defaultEnabled catalogue metadata ("recommended") - never a runtime
 *     fallback, see {@link ModerationPolicyPack#defaultEnabled()}
 * @param available false when the pack is held for legal review and the write
 *     path will refuse to enable it
 * @param unavailableReason operator-facing reason when {@code !available}
 * @param updatedBy who wrote the deciding setting row, when one exists
 * @param updatedAt when the deciding setting row was written, ISO-8601
 * @param scopeOfEffectiveValue which level decided {@code enabled} - the
 *     requested scope itself, {@code GLOBAL} by inheritance, or null when no
 *     row exists anywhere (engine default: off)
 * @param explicitlySetAtThisScope true when the requested scope has its own
 *     row - i.e. clearing it (not just toggling) would change inheritance
 */
public record ModerationPackState(
    String id,
    String displayName,
    String description,
    String enforcement,
    boolean enabled,
    boolean defaultEnabled,
    boolean available,
    String unavailableReason,
    String updatedBy,
    String updatedAt,
    ModerationPolicyScope scopeOfEffectiveValue,
    boolean explicitlySetAtThisScope
) {
}
