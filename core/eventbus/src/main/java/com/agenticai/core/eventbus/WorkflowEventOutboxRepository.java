package com.agenticai.core.eventbus;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEventOutboxRepository extends JpaRepository<WorkflowEventOutbox, UUID> {

    List<WorkflowEventOutbox> findByPublishedAtIsNull();

    // #913/#914 - the relay's real poll query as of the retry-cap hardening
    // (V81): excludes rows that have already dead-lettered (retryCount >= the
    // cap), so a permanently-broken row stops being re-attempted every 5s
    // forever once WorkflowEventOutboxRelay has already raised a PlatformIssue
    // for it. findByPublishedAtIsNull() above is left in place (unused by the
    // relay now, but a harmless, still-correct query) rather than deleted -
    // no other caller depended on distinguishing the two before this change.
    List<WorkflowEventOutbox> findByPublishedAtIsNullAndRetryCountLessThan(int maxRetries);

    // #916 (Kafka dashboard wiring) - cheap counts for the Dashboard's
    // "queue" panel, scoped to whichever tenant schema is active on the
    // calling thread (same schema-per-tenant routing every other query on
    // this repository already gets - no cross-tenant aggregation needed
    // here since SystemStatusController's caller is always one tenant).
    long countByPublishedAtIsNullAndRetryCountLessThan(int maxRetries);

    long countByPublishedAtIsNullAndRetryCountGreaterThanEqual(int maxRetries);
}
