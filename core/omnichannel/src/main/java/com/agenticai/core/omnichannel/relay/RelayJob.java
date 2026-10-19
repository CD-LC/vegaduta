package com.agenticai.core.omnichannel.relay;

import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import java.util.UUID;

/**
 * One inbound channel message handed to a browser relay (2026-10-14, "edge relay"). Stored as
 * JSON in Valkey for the life of the hand-off and returned verbatim to the relay by {@code GET
 * /api/channels/edge-relay/{relayId}/next}. Carries everything the relay needs to answer with the
 * local model and nothing it does not: the agent id (the relay fetches the persona itself, as the
 * signed-in tenant user it is), the message, and the recent history of this session so a freshly
 * opened browser has context. The sender's phone number is masked; the relay never sends, the
 * server thread that owns the inbound message does, through the normal approval gate.
 *
 * <p>{@code status} is {@code QUEUED}, {@code CLAIMED}, {@code DONE} or {@code CANCELLED}; the
 * server cancels a job whose reply did not arrive within the reply window and answers it on the
 * hosted path instead, and a late reply to a cancelled job is dropped.
 */
public record RelayJob(
        String jobId,
        String tenantId,
        ChannelType channel,
        UUID sessionId,
        UUID agentId,
        String externalIdMasked,
        String message,
        List<RelayTurn> history,
        long createdAtEpochMs,
        String status,
        String claimedBy) {

    public static final String QUEUED = "QUEUED";
    public static final String CLAIMED = "CLAIMED";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";

    public RelayJob withStatus(String newStatus, String newClaimedBy) {
        return new RelayJob(
                jobId, tenantId, channel, sessionId, agentId, externalIdMasked, message, history,
                createdAtEpochMs, newStatus, newClaimedBy);
    }

    /** One earlier message of the session: {@code role} is {@code user} or {@code assistant}. */
    public record RelayTurn(String role, String text) {}
}
