package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and failure modes for signed Agent Cards: the wire shape
 * ({@code signatures[].protected/signature/header}), tamper detection, wrong
 * key rejection, and the pinned-key config paths. Plain unit tests - keys are
 * generated in memory, nothing touches the network or the servlet layer.
 */
class A2aCardSigningServiceTest {

    private static Map<String, Object> sampleCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "VegaDuta");
        card.put("protocolVersion", "1.0");
        card.put("url", "https://api.example.test/api/a2a");
        card.put("capabilities", Map.of("streaming", true));
        card.put("skills", List.of());
        return card;
    }

    private static A2aProperties signingProps() {
        A2aProperties props = new A2aProperties();
        props.getSigning().setEnabled(true);
        return props;
    }

    @Test
    void signedCardVerifiesAgainstItsOwnPublicKey() {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        Map<String, Object> card = sampleCard();
        List<Map<String, Object>> signatures = signing.sign(card);
        assertEquals(1, signatures.size());

        Map<String, Object> entry = signatures.get(0);
        assertNotNull(entry.get("protected"));
        assertNotNull(entry.get("signature"));
        assertEquals(signing.keyId(), ((Map<?, ?>) entry.get("header")).get("kid"));

        card.put("signatures", signatures);
        assertTrue(signing.verify(card, signing.publicKey()));
    }

    @Test
    void tamperedCardFailsVerification() {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        Map<String, Object> card = sampleCard();
        card.put("signatures", signing.sign(card));

        card.put("url", "https://evil.example.test/api/a2a");
        assertFalse(signing.verify(card, signing.publicKey()));
    }

    @Test
    void signatureFromAnotherKeyFailsVerification() throws Exception {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        Map<String, Object> card = sampleCard();
        card.put("signatures", signing.sign(card));

        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertFalse(signing.verify(card, other.getPublic()));
    }

    @Test
    void unsignedOrMalformedCardsFailClosed() {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        assertFalse(signing.verify(sampleCard(), signing.publicKey()));

        Map<String, Object> card = sampleCard();
        card.put("signatures", List.of(Map.of("protected", "not-a-header")));
        assertFalse(signing.verify(card, signing.publicKey()));
    }

    @Test
    void disabledSigningEmitsNoSignatures() {
        A2aProperties props = new A2aProperties();
        props.getSigning().setEnabled(false);
        A2aCardSigningService signing = new A2aCardSigningService(props);
        assertTrue(signing.sign(sampleCard()).isEmpty());
    }

    @Test
    void publicJwkIsAnOkpEd25519Key() {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        Map<String, Object> jwk = signing.publicJwk();
        assertEquals("OKP", jwk.get("kty"));
        assertEquals("Ed25519", jwk.get("crv"));
        assertEquals("sig", jwk.get("use"));
        assertNotNull(jwk.get("x"));
        assertEquals(signing.keyId(), jwk.get("kid"));
    }

    @Test
    void privateKeyWithoutPublicKeyIsRejectedLoudly() {
        A2aProperties props = signingProps();
        props.getSigning().setPrivateKeyPem("aabbccddee"); // hex seed, no public key
        A2aCardSigningService signing = new A2aCardSigningService(props);
        assertThrows(IllegalStateException.class, signing::keyId);
    }

    @Test
    void pinnedKeyPairProducesAStableKid() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        java.util.Base64.Encoder b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes());
        String privPem = "-----BEGIN PRIVATE KEY-----\n" + b64.encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        String pubPem = "-----BEGIN PUBLIC KEY-----\n" + b64.encodeToString(pair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----";

        A2aProperties props = signingProps();
        props.getSigning().setPrivateKeyPem(privPem);
        props.getSigning().setPublicKeyPem(pubPem);
        A2aCardSigningService a = new A2aCardSigningService(props);
        A2aCardSigningService b = new A2aCardSigningService(props);

        assertEquals(a.keyId(), b.keyId());
        Map<String, Object> card = sampleCard();
        card.put("signatures", a.sign(card));
        // cross-instance: a card signed by "a" verifies under "b"'s public key
        assertTrue(b.verify(card, b.publicKey()));
    }

    @Test
    void requirePinnedKeyFailsFastWhenNoKeyIsPinned() {
        A2aProperties props = signingProps();
        props.setRequirePinnedKey(true);
        A2aCardSigningService signing = new A2aCardSigningService(props);

        assertThrows(IllegalStateException.class, signing::checkKeyPinning);
    }

    @Test
    void requirePinnedKeyIsIgnoredWhenSigningIsDisabled() {
        A2aProperties props = signingProps();
        props.getSigning().setEnabled(false);
        props.setRequirePinnedKey(true);

        new A2aCardSigningService(props).checkKeyPinning();
    }

    @Test
    void ephemeralKeyOnlyWarnsAndNeverFailsByDefault() {
        A2aCardSigningService signing = new A2aCardSigningService(signingProps());
        signing.setReplicaMode("api");

        // default require-pinned-key=false: the multi-replica hint WARNs but boot proceeds
        signing.checkKeyPinning();
        signing.setReplicaMode("");
        signing.checkKeyPinning();
    }
}
