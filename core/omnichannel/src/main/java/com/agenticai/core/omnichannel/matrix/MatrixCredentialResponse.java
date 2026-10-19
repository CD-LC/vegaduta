package com.agenticai.core.omnichannel.matrix;

import java.time.Instant;
import java.util.UUID;

/**
 * The stored connection, never the token. {@code inboundActive} says whether this process runs the
 * sync loop ({@code app.matrix.sync.enabled}); without it the bot can send but receives nothing.
 */
public record MatrixCredentialResponse(
        UUID id,
        String homeserverUrl,
        String botUserId,
        String deviceId,
        MatrixCredentialStatus status,
        MatrixJoinPolicy joinPolicy,
        MatrixSenderMode senderMode,
        String allowedSenders,
        boolean inboundActive,
        Instant createdAt,
        Instant lastValidatedAt) {

    public static MatrixCredentialResponse from(MatrixCredential c, boolean inboundActive) {
        return new MatrixCredentialResponse(
                c.getId(),
                c.getHomeserverUrl(),
                c.getBotUserId(),
                c.getDeviceId(),
                c.getStatus(),
                c.getJoinPolicy(),
                c.getSenderMode(),
                c.getAllowedSenders(),
                inboundActive,
                c.getCreatedAt(),
                c.getLastValidatedAt());
    }
}
