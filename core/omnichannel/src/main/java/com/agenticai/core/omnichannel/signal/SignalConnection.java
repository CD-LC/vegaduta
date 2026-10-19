package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import java.util.Objects;

/**
 * Everything needed to talk to one tenant's signal-cli REST API, decrypted. Lives only for one
 * request or one polling iteration. {@link #toString()} never prints the token.
 *
 * @param authToken optional bearer token for a proxy in front of signal-cli; null when none
 */
public record SignalConnection(
        String baseUrl,
        String accountNumber,
        String authToken,
        UnknownSenderPolicy unknownSenders,
        SignalGroupMode groupMode) {

    public SignalConnection {
        if (authToken != null && authToken.isBlank()) {
            authToken = null;
        }
        unknownSenders = unknownSenders == null ? UnknownSenderPolicy.PAIR : unknownSenders;
        groupMode = groupMode == null ? SignalGroupMode.IGNORE : groupMode;
    }

    public boolean hasAuthToken() {
        return authToken != null;
    }

    /** Same endpoint and identity, ignoring the policy fields (which can change live). */
    public boolean sameEndpoint(SignalConnection other) {
        return other != null
                && baseUrl.equals(other.baseUrl)
                && accountNumber.equals(other.accountNumber)
                && Objects.equals(authToken, other.authToken);
    }

    @Override
    public String toString() {
        return "SignalConnection[baseUrl="
                + baseUrl
                + ", account=***"
                + (accountNumber.length() > 4
                        ? accountNumber.substring(accountNumber.length() - 4)
                        : "")
                + ", authToken="
                + (authToken == null ? "none" : "***")
                + "]";
    }
}
