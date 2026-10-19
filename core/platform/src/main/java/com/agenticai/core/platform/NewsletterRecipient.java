package com.agenticai.core.platform;

import java.util.UUID;

/**
 * One row of {@code GET /api/platform/newsletter/recipients} - a candidate
 * tenant for the platform-admin Newsletter/Broadcast feature.
 *
 * <p>{@code adminEmail} is {@code null} when the tenant has no {@code
 * TENANT_ADMIN}-role {@code control_plane.app_user} row yet (e.g. a tenant
 * still mid-provisioning) - such a tenant is unreachable by this feature
 * regardless of its opt-in flag, surfaced here (rather than silently
 * dropped) so the web-admin UI can show why the row can't be selected.
 *
 * <p>{@code newsOptIn} mirrors {@code control_plane.tenant.news_opt_in} -
 * the per-tenant opt-in captured at signup (see this repo's signup flow;
 * that column/value is owned by a separate workstream, not this feature).
 * {@link NewsletterService#send} only ever mails tenants where this is
 * {@code true}, no matter what the caller passes in {@code
 * recipientTenantIds} - this field is what lets the picker UI show, for
 * every tenant it lists (selected or not), whether a send would actually
 * reach them.
 */
public record NewsletterRecipient(UUID tenantId, String name, String adminEmail, boolean newsOptIn) {
}
