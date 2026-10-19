package com.agenticai.core.omnichannel.session;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelMessageLogRepository extends JpaRepository<ChannelMessageLog, UUID> {

    /** #604 - "read all messages from telegram" tool: most recent N for one channel, across every sender. */
    List<ChannelMessageLog> findTop50ByChannelOrderByCreatedAtDesc(ChannelType channel);

    /** Same, narrowed to one external sender - not wired to a tool yet, but a natural next filter. */
    List<ChannelMessageLog> findTop50ByChannelAndExternalIdOrderByCreatedAtDesc(ChannelType channel, String externalId);

    /** #815 Governance follow-up - data-subject erasure ({@code ChannelSessionService#eraseDataSubject}). Returns the row count deleted. */
    long deleteByChannelAndExternalId(ChannelType channel, String externalId);

    /**
     * Live human handoff (V214) - one session's transcript for the operator
     * inbox's conversation view, served by {@code
     * idx_channel_message_log_session_created}. Newest-first with a hard cap
     * (the service reverses into display order) rather than unbounded
     * ascending, so a months-old conversation with thousands of rows cannot
     * balloon one inbox click into a full-table read - same bounded-read
     * posture as the Top50 finders above.
     */
    List<ChannelMessageLog> findTop200ByChannelSessionIdOrderByCreatedAtDesc(UUID channelSessionId);

    // Cross-session conversation search (P1 developer-extensibility). Four
    // explicit derived finders rather than one "(:param IS NULL OR ...)"
    // @Query - that pattern is exactly what hit the Postgres/Hibernate
    // parameter-type-inference bug documented in AuditLogService's javadoc,
    // and four one-purpose methods are clearer than a Specification for a
    // two-optional-filter search. Containing = LIKE %q%, case-insensitive;
    // the impl strips the caller's own %/_ so user input can't inject LIKE
    // wildcards.

    /** Search every channel and sender. */
    List<ChannelMessageLog> findTop50ByBodyContainingIgnoreCaseOrderByCreatedAtDesc(String body);

    /** Search one channel, every sender. */
    List<ChannelMessageLog> findTop50ByChannelAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(ChannelType channel, String body);

    /** Search one sender, every channel. */
    List<ChannelMessageLog> findTop50ByExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(String externalId, String body);

    /** Search one sender on one channel. */
    List<ChannelMessageLog> findTop50ByChannelAndExternalIdAndBodyContainingIgnoreCaseOrderByCreatedAtDesc(
        ChannelType channel, String externalId, String body);
}
