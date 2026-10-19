package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Where the operator's signal-cli REST API is and which registered number the agent speaks as.
 * Both are proven against that server before anything is stored ({@code
 * SignalCredentialService#activate}).
 *
 * @param baseUrl e.g. {@code http://signal-cli:8080}
 * @param number the registered number, e.g. {@code +4915112345678}
 * @param authToken optional bearer token for a reverse proxy in front of signal-cli
 * @param unknownSenders default {@code PAIR}
 * @param groupMode default {@code IGNORE}
 */
public record SignalActivationRequest(
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 32) String number,
        @Size(max = 4096) String authToken,
        UnknownSenderPolicy unknownSenders,
        SignalGroupMode groupMode) {
}
