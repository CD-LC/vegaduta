package com.agenticai.core.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;

/**
 * SPI for accepting platform-issued <b>delegation tokens</b> (agent acting
 * for a user) as API credentials, alongside the Keycloak JWT path.
 *
 * <p>Implemented by {@code core/agents}'s delegation service; consumed by
 * {@link DelegationTokenAuthFilter} here in the security module - the
 * interface lives in this module precisely because agents already depends on
 * security and the reverse would be a cycle. {@link Optional}-empty means
 * "this bearer token isn't a valid delegation token" - the normal JWT path
 * then handles (or rejects) it, so a bad delegation token degrades to an
 * ordinary 401 rather than a confusing success-shaped failure.
 */
public interface DelegatedTokenAuthenticator {

    /**
     * Verify {@code bearerToken} (the value after {@code "Bearer "}) as a
     * delegation token. Return a {@link DelegationAuthenticationToken} when
     * the token is ours, unexpired, unrevoked and audience-bound to this API;
     * {@link Optional#empty()} otherwise (including "not a delegation token
     * at all" - Keycloak JWTs take the normal path).
     */
    Optional<Authentication> authenticate(String bearerToken);
}
