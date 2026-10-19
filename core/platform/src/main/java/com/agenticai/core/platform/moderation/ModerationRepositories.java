package com.agenticai.core.platform.moderation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The control-plane repository for the moderation admin surface's blocklists.
 *
 * <p>This file used to hold three repositories; the other two
 * ({@code ModerationPolicyPackSettingRepository},
 * {@code PlatformModerationReviewItemRepository}) were deleted in the 2026-08
 * moderation unification along with their entities: their tables duplicated
 * state the live core/guardrails engine keeps elsewhere ({@code
 * platform_setting} keys; the per-tenant-schema review queue from tenant
 * V121), so the admin surface was rewired onto the engine's own stores and
 * V216 dropped the two orphan tables. Kept as a holder file rather than
 * promoting the survivor to its own file so the file rename doesn't churn the
 * package for one interface - and so this note has somewhere to live.
 */
final class ModerationRepositories {
    private ModerationRepositories() {
    }
}

interface ModerationBlocklistEntryRepository extends JpaRepository<ModerationBlocklistEntry, UUID> {

    List<ModerationBlocklistEntry> findByEntryTypeOrderByCreatedAtDesc(ModerationBlocklistType entryType);

    List<ModerationBlocklistEntry> findByOrderByCreatedAtDesc();

    Optional<ModerationBlocklistEntry> findByEntryTypeAndValue(ModerationBlocklistType entryType, String value);

    List<ModerationBlocklistEntry> findByActiveTrue();
}
