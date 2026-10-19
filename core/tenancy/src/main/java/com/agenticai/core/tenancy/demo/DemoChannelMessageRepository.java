package com.agenticai.core.tenancy.demo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemoChannelMessageRepository extends JpaRepository<DemoChannelMessage, UUID> {

    /**
     * The {@code /try} poll. {@code since} is exclusive and comes from the
     * previous poll's newest {@code createdAt}, so a browser that has been
     * open for an hour still transfers only what is new.
     */
    @Query(
        "SELECT m FROM DemoChannelMessage m WHERE m.demoSessionId = :sessionId AND m.channel = :channel "
            + "AND m.createdAt > :since ORDER BY m.createdAt ASC"
    )
    List<DemoChannelMessage> findSince(
        @Param("sessionId") UUID sessionId,
        @Param("channel") DemoChannelType channel,
        @Param("since") Instant since,
        Pageable pageable
    );

    /**
     * Prune support. JPQL has no {@code LIMIT}/{@code OFFSET} in a subquery,
     * so the prune is two cheap queries instead of one clever one: ask for the
     * timestamp of the (keep+1)-th newest row via {@link Pageable}, then
     * delete everything strictly older. Both hit
     * {@code idx_demo_channel_message_session_time}.
     */
    @Query("SELECT m.createdAt FROM DemoChannelMessage m WHERE m.demoSessionId = :sessionId ORDER BY m.createdAt DESC")
    List<Instant> findCreatedAtDescending(@Param("sessionId") UUID sessionId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM DemoChannelMessage m WHERE m.demoSessionId = :sessionId AND m.createdAt < :cutoff")
    int deleteOlderThan(@Param("sessionId") UUID sessionId, @Param("cutoff") Instant cutoff);

    /**
     * The transcript-email fallback source ({@link DemoTranscriptEmailService})
     * - everything the mirror buffer still holds for this session, across ALL
     * channels, oldest first. Small by construction: the prune above keeps
     * only the most recent handful of rows per session, which is also why the
     * email is honest about being a recent excerpt.
     */
    List<DemoChannelMessage> findByDemoSessionIdOrderByCreatedAtAsc(UUID demoSessionId);
}
