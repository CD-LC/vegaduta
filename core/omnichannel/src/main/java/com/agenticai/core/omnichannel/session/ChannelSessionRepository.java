package com.agenticai.core.omnichannel.session;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChannelSessionRepository extends JpaRepository<ChannelSession, UUID> {

    Optional<ChannelSession> findByChannelAndExternalId(ChannelType channel, String externalId);

    /**
     * Live human handoff (V214) - the operator inbox: every escalated or
     * human-held conversation, newest activity first. Served by the partial
     * index {@code idx_channel_session_handoff_live}.
     */
    List<ChannelSession> findByHandoffStateInOrderByLastMessageAtDesc(Collection<HandoffState> states);

    /**
     * Live human handoff (V214) - resolves the agent-facing {@code
     * sessionId} (the Redis conversation-memory key {@code AgentChatService}
     * runs under) back to its channel session. This is the lookup the
     * designed-but-not-yet-written {@code request_human} agent tool's
     * {@code HumanHandoffPort} implementation needs: the tool only knows its
     * own session id, never the channel session PK. {@code findFirst}
     * rather than a plain {@code Optional} finder because {@code session_id}
     * carries no UNIQUE constraint (it is a random UUID string minted at
     * session creation - collisions are practically impossible but the
     * finder should not throw if one ever occurred).
     */
    Optional<ChannelSession> findFirstBySessionId(String sessionId);

    /** Autonomy/Guardrails Strategy Stage A - ChannelPairingController's pending list. */
    List<ChannelSession> findByPairedFalseOrderByCreatedAtDesc();

    /** Contacts import reconcile (V238) - pending sessions on ONE channel, matched against a just-saved contact in Java (digit-normalized phone matching can't be a JPQL predicate). Pending lists are small. */
    List<ChannelSession> findByChannelAndPairedFalse(ChannelType channel);

    /** AXON Channels screen (#472) - real "N sessions" count per channel card, paired only (unpaired = still in the pending-pairing inbox, not a live conversation). */
    long countByChannelAndPairedTrue(ChannelType channel);

    /** AXON Channels screen - real "last activity" timestamp per channel card. */
    Optional<ChannelSession> findFirstByChannelAndPairedTrueOrderByLastMessageAtDesc(ChannelType channel);

    /** AXON Channels screen - "RECENT INBOUND" log, newest activity across every channel. */
    List<ChannelSession> findTop10ByOrderByLastMessageAtDesc();

    /**
     * Operator inbox "All conversations" view (2026-09-23) - the whole
     * session table behind one paged, optionally-filtered read: {@code
     * channel} narrows to one channel, {@code state} to one handoff state,
     * {@code paired} splits live conversations from the pending-pairing
     * queue, and {@code q} is a substring match over the routing id, the
     * resolved phone number and the last-message preview (the three fields
     * an operator would realistically type). Null channel/state/paired mean
     * "no constraint" so the one query serves every tab of the inbox filter
     * bar. Ordered by last activity, newest first - the same ordering every
     * other session list on this screen uses.
     *
     * <p>{@code q} is the one exception to "null means no constraint": it
     * must be the empty string instead. A null String parameter whose only
     * typed context is {@code concat('%', :q, '%')} gets no inferred JDBC
     * type from Hibernate and is bound as VARBINARY on Postgres - the whole
     * {@code '%'||?||'%'} pattern then resolves to bytea and the query dies
     * with {@code ERROR: function lower(bytea) does not exist} (seen in
     * prod on 2026-09-23, breaking the inbox's default unfiltered view).
     * {@code :q = ''} short-circuits the LIKEs exactly like the old
     * {@code :q is null} check while keeping every bind a real varchar;
     * callers pass {@code ""} for "no search text".
     */
    @Query("""
        select s from ChannelSession s
        where (:channel is null or s.channel = :channel)
          and (:state is null or s.handoffState = :state)
          and (:paired is null or s.paired = :paired)
          and (:q = ''
               or lower(s.externalId) like lower(concat('%', :q, '%'))
               or lower(coalesce(s.lastMessagePreview, '')) like lower(concat('%', :q, '%'))
               or lower(coalesce(s.resolvedPhoneNumber, '')) like lower(concat('%', :q, '%')))
        order by s.lastMessageAt desc
        """)
    Page<ChannelSession> searchConversations(
        @Param("channel") ChannelType channel,
        @Param("state") HandoffState state,
        @Param("paired") Boolean paired,
        @Param("q") String q,
        Pageable pageable
    );

    /** Operator inbox stats strip - one count per handoff state / pairing flag. */
    long countByHandoffState(HandoffState state);

    long countByPairedFalse();

    long countByHandoffStateAndPairedTrue(HandoffState state);
}
