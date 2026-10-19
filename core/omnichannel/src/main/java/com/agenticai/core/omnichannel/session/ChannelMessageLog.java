package com.agenticai.core.omnichannel.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One inbound message, kept as a real per-message log (migration V50) - #604
 * ("read all messages from telegram"). {@link ChannelSession#getLastMessagePreview()}
 * (V49) only ever holds the SINGLE most recent message, overwritten on every
 * {@link ChannelSession#touch(String)}; that was enough for the Channels
 * screen's "what did it last say" glance, but not for a tool that needs to
 * answer "read all messages from telegram" - hence this separate,
 * append-only table.
 *
 * <p>{@code channelSessionId} is a plain UUID column, not a JPA
 * {@code @ManyToOne} - same reasoning as {@link ChannelSession#getAgentId()}:
 * writing a log row should never require loading the full session, and (per
 * migration V50's comment) deliberately has no FK constraint so a session
 * rebind/deletion can never block appending the message that caused it.
 */
@Entity
@Table(name = "channel_message_log")
public class ChannelMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType channel;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "channel_session_id")
    private UUID channelSessionId;

    @Column(nullable = false, length = 2000)
    private String body;

    // Live human handoff (migration V214) - which way this message
    // travelled. Every row written before V214 is INBOUND by construction
    // (this table had exactly one write site, resolveOrCreate) and the
    // column DEFAULT backfills them accordingly. See ChannelMessageDirection.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ChannelMessageDirection direction;

    // NULL for inbound rows (the sender is already named by external_id);
    // the operator's Keycloak subject for OUTBOUND operator replies. Opaque
    // VARCHAR, never an FK - same reasoning as channel_session.taken_over_by.
    @Column(length = 255)
    private String author;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChannelMessageLog() {
        // JPA
    }

    /**
     * The V50-era inbound constructor, unchanged in signature so
     * {@code ChannelSessionService#resolveOrCreate} keeps compiling and
     * writing exactly what it always wrote - now with the direction made
     * explicit instead of implicit.
     */
    public ChannelMessageLog(ChannelType channel, String externalId, UUID channelSessionId, String body) {
        this(channel, externalId, channelSessionId, body, ChannelMessageDirection.INBOUND, null);
    }

    /**
     * Full constructor (V214) - the outbound half of the transcript.
     * {@code externalId} on an OUTBOUND row is the RECIPIENT's external id
     * (the same value the paired inbound rows carry), deliberately so
     * {@code ChannelSessionService#eraseDataSubject}'s existing
     * delete-by-(channel, external_id) erases both sides of a data
     * subject's conversation with no code change - see V214's header.
     */
    public ChannelMessageLog(
        ChannelType channel, String externalId, UUID channelSessionId, String body,
        ChannelMessageDirection direction, String author
    ) {
        this.channel = channel;
        this.externalId = externalId;
        this.channelSessionId = channelSessionId;
        this.body = truncate(body);
        this.direction = direction;
        this.author = author;
        this.createdAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    public UUID getId() {
        return id;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public UUID getChannelSessionId() {
        return channelSessionId;
    }

    public String getBody() {
        return body;
    }

    public ChannelMessageDirection getDirection() {
        return direction;
    }

    public String getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
