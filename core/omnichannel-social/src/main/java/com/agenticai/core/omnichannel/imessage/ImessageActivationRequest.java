package com.agenticai.core.omnichannel.imessage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The BlueBubbles server address and password. The password is the one set in BlueBubbles'
 * "Server Password" setting; it is stored encrypted and never returned. {@code inboundMode} is
 * optional (default WEBHOOK).
 */
public record ImessageActivationRequest(
        @NotBlank @Size(max = 512) String serverUrl,
        @NotBlank @Size(max = 512) String password,
        ImessageInboundMode inboundMode) {

    /** Keeps the password out of any accidental {@code toString()} in a log line. */
    @Override
    public String toString() {
        return "ImessageActivationRequest[serverUrl=" + serverUrl + ", password=***, inboundMode=" + inboundMode + "]";
    }
}
