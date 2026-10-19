package com.agenticai.core.platform;

/**
 * Body for {@code POST /api/platform/promos/unlimited-14d} (see {@code
 * PlatformController#grantSiteWideUnlimitedPromo}). {@code sendEmail}
 * defaults false intentionally - a mass email to every registered tenant's
 * admin is a separate, explicit action from issuing the plan grant itself.
 */
public record SiteWidePromoRequest(int durationDays, String reason, boolean sendEmail) {
}
