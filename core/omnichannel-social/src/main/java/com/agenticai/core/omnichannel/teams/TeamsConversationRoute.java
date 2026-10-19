package com.agenticai.core.omnichannel.teams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The per-conversation Bot Framework routing target: the {@code serviceUrl} an activity arrived on,
 * needed to POST a reply back through the connector API ({@code
 * {serviceUrl}v3/conversations/{id}/activities}). Kept out of {@code ChannelSession.external_id}
 * for two reasons: that column caps at 64 chars (a serviceUrl alone can exceed it), and the
 * serviceUrl is mutable routing metadata rather than part of the stable identity - the conversation
 * id stays the identity; this row just records where it was last reachable. Rewritten on every
 * inbound activity (upsert), so async sends - operator inbox replies, workflow {@code teams} Output
 * nodes, manual sends - always use the freshest endpoint.
 */
@Entity
@Table(name = "teams_conversation_route")
public class TeamsConversationRoute {

    @Id
    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "service_url", nullable = false, length = 255)
    private String serviceUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TeamsConversationRoute() {
        // JPA
    }

    public TeamsConversationRoute(String conversationId, String serviceUrl) {
        this.conversationId = conversationId;
        this.serviceUrl = serviceUrl;
        this.updatedAt = Instant.now();
    }

    public void refresh(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        this.updatedAt = Instant.now();
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
