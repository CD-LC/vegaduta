package com.agenticai.core.security;

import java.time.Duration;
import java.time.Instant;

/**
 * FREE-tier edit-lock policy - Subscription Plan &amp; GTM Strategy doc
 * (project root, Section 4): once a FREE-plan tenant's agent or workflow is
 * older than {@link #FREE_TIER_GRACE_WINDOW}, it becomes read-only. The
 * object stays fully usable (chat/run/view/delete are never affected by this
 * check - only the mutating config/definition endpoints call it), and
 * duplicating a locked object creates a new one with its own fresh {@code
 * createdAt}, so this is a pure plan+timestamp check - no new "locked" column
 * on Agent/Workflow was needed to build this.
 *
 * <p>PREMIUM and UNLIMITED tenants are never locked, regardless of age.
 */
public final class EditLockPolicy {

    /** Section 4, Decision 1 (confirmed by product owner): 24 hours after creation. */
    public static final Duration FREE_TIER_GRACE_WINDOW = Duration.ofHours(24);

    private EditLockPolicy() {
    }

    /**
     * @param plan the tenant's current subscription tier
     * @param createdAt the object's creation timestamp; {@code null} is treated as "not locked"
     *     (a defensive default - every real Agent/Workflow row always has one)
     * @return true if this plan+age combination should reject mutating edits
     */
    public static boolean isLocked(TenantPlan plan, Instant createdAt) {
        if (plan != TenantPlan.FREE || createdAt == null) {
            return false;
        }
        return Instant.now().isAfter(createdAt.plus(FREE_TIER_GRACE_WINDOW));
    }
}
