package com.agenticai.core.agents.branching;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention for the conversation tree ({@code chat_branch_*}, V450). Redis history expires after
 * {@code app.chat.session-ttl-hours} (24h), but the tree keeps the message TEXT durably so a branch can still
 * be switched to after the live list is gone; without a bound that text would outlive what operators and
 * users believe the chat retention to be. This sweep deletes whole sessions (state row plus every node) whose
 * branching state was last touched more than {@code app.chat.branching.retention-days} ago (default 30,
 * floored at 1). A session the user comes back to after that is simply seeded again from Redis, if Redis still
 * has it, and is otherwise a fresh conversation. Per-user erasure is {@link ChatBranchAccountDeletionListener};
 * a whole-tenant erasure drops the schema and these tables with it.
 *
 * <p>Inert while {@code app.chat.branching.enabled=false} (nothing is ever written then). Same shape as the other
 * scheduled tenant sweeps: Postgres advisory lock so replicas do not all purge at once (a convenience, the
 * delete is idempotent), the tenant-schema iterator because a {@code @Scheduled} thread has no
 * {@code TenantContext}, and a try/catch per schema.
 */
@Component
public class ChatBranchRetentionPurge {

    private static final Logger log = LoggerFactory.getLogger(ChatBranchRetentionPurge.class);
    private static final String PURGE_LOCK = "chat-branch-retention-purge";

    private final ChatBranchStore store;
    private final TenantSchemaIterator schemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final boolean enabled;
    private final int retentionDays;

    public ChatBranchRetentionPurge(
        ChatBranchStore store,
        TenantSchemaIterator schemaIterator,
        PgAdvisoryJobLock jobLock,
        @Value("${app.chat.branching.enabled:false}") boolean enabled,
        @Value("${app.chat.branching.retention-days:30}") int retentionDays
    ) {
        this.store = store;
        this.schemaIterator = schemaIterator;
        this.jobLock = jobLock;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(
        fixedDelayString = "${app.chat.branching.retention-purge-interval:6h}",
        initialDelayString = "${app.chat.branching.retention-purge-interval:6h}"
    )
    public void purge() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(PURGE_LOCK, () -> purgeOlderThan(Instant.now().minus(Duration.ofDays(retentionDays))));
    }

    /** One pass over every active tenant schema; package-private so the cutoff is testable without a clock. */
    void purgeOlderThan(Instant cutoff) {
        AtomicInteger total = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            try {
                total.addAndGet(store.deleteSessionsIdleBefore(cutoff));
            } catch (RuntimeException e) {
                log.warn("Chat branch retention purge failed in schema '{}'", schema, e);
            }
        });
        if (total.get() > 0) {
            log.info("Chat branch retention purge removed {} session tree(s) idle for more than {} day(s)",
                total.get(), retentionDays);
        }
    }
}
