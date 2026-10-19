package com.agenticai.core.omnichannel.matrix;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The homeserver and the bot user's access token. The bot's MXID and device id come back from the
 * homeserver's own {@code whoami} during activation ("prove it before persisting"), never from what
 * the tenant typed. {@code joinPolicy}, {@code senderMode} and {@code allowedSenders} are optional
 * and default to the closed settings (never join on invite, any sender goes through pairing).
 */
public record MatrixActivationRequest(
        @NotBlank @Size(max = 512) String homeserverUrl,
        @NotBlank @Size(max = 4096) String accessToken,
        MatrixJoinPolicy joinPolicy,
        MatrixSenderMode senderMode,
        @Size(max = 20000) String allowedSenders) {

    /** A record prints every component by default; the token must never reach a log line. */
    @Override
    public String toString() {
        return "MatrixActivationRequest[homeserverUrl=" + homeserverUrl + ", accessToken=***]";
    }
}
