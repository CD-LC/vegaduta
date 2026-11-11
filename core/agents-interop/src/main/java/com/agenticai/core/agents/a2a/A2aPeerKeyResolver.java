package com.agenticai.core.agents.a2a;

import java.security.PublicKey;
import java.util.Optional;

/**
 * Finds the public key a caller signs its A2A requests with: the key that verified the caller's own
 * Agent Card (the card URL travels in the {@code X-Vegaduta-A2A-Origin} header). An interface so
 * tests can pin keys without a network.
 */
public interface A2aPeerKeyResolver {

    /**
     * @param cardUrl the caller's Agent Card URL, as the caller stated it
     * @param keyId the {@code kid} the request's signature names
     * @return the key, or empty when the card cannot be fetched, is not validly signed, names a
     *     different key id, or is not on the operator's trusted-host list
     */
    Optional<PublicKey> resolve(String cardUrl, String keyId);
}
