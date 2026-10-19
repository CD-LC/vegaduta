package com.agenticai.core.security.localauth;

import java.util.Map;
import java.util.Optional;

/**
 * Extension seam for ways of proving who is signing in to a local-mode install. The shipped
 * implementation checks a password ({@code type() == "password"}); a passkey (WebAuthn) verifier is
 * meant to be added here without touching the session code: it implements this interface (or
 * registers its own endpoints for the challenge round trip), and on success the account service
 * starts the same session - same access token, same rotating refresh token - recording the method
 * in the token's {@code amr} claim.
 *
 * <p>Implementations must be constant-time with respect to whether the identifier exists (verify
 * against a dummy credential when it does not), and must never log the submitted credentials.
 */
public interface LocalCredentialVerifier {

    /** The credential type this verifier handles, e.g. {@code "password"} or {@code "passkey"}. */
    String type();

    /** The value recorded in the access token's {@code amr} claim after a successful check. */
    String authenticationMethod();

    /**
     * Verifies {@code credentials} for {@code identifier}.
     *
     * @return the account id when the credentials are valid for an enabled account, else empty
     */
    Optional<String> verify(String identifier, Map<String, String> credentials);
}
