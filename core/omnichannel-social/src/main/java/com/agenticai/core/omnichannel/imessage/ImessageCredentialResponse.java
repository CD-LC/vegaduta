package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The connection as the console shows it. The server password is never included.
 * {@code webhookPath} and {@code webhookToken} are what the operator pastes into BlueBubbles
 * (Settings, API &amp; Webhooks) as {@code <this platform's public URL><webhookPath>?token=<webhookToken>};
 * the token is a bearer secret for posting inbound messages, so this endpoint is tenant-admin only.
 */
public record ImessageCredentialResponse(
        UUID id,
        String serverUrl,
        ImessageCredentialStatus status,
        ImessageInboundMode inboundMode,
        List<String> allowedSenders,
        boolean allowGroups,
        UnknownSenderPolicy unknownSenderPolicy,
        String webhookPath,
        String webhookToken,
        Instant createdAt,
        Instant lastValidatedAt) {}
