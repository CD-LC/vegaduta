package com.agenticai.core.platform.auditseal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.audit.seal.AuditSealSigner;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditSealA2aSignerTest {

    private static A2aProperties pinned(boolean pinned) {
        A2aProperties props = new A2aProperties();
        props.getSigning().setPrivateKeyPem(pinned ? "PINNED" : "");
        return props;
    }

    @Test
    void delegatesToTheOperatorKeyAndReturnsItsPublicKeyAndKid() {
        A2aCardSigningService signing = mock(A2aCardSigningService.class);
        when(signing.isEnabled()).thenReturn(true);
        when(signing.signCompactJws(any(), any())).thenReturn("h.p.s");
        when(signing.publicJwk()).thenReturn(Map.of("kty", "OKP", "x", "PUBKEY"));
        when(signing.keyId()).thenReturn("a2a-kid");

        AuditSealA2aSigner signer = new AuditSealA2aSigner(signing, pinned(true));
        assertTrue(signer.isAvailable());
        Optional<AuditSealSigner.Signed> signed = signer.sign(Map.of("seal_hash", "abc"));

        assertTrue(signed.isPresent());
        assertEquals("h.p.s", signed.get().compactJws());
        assertEquals("a2a-kid", signed.get().keyId());
        assertEquals("PUBKEY", signed.get().publicKeyBase64Url());
    }

    @Test
    void isUnavailableWhenSigningIsDisabledOrFails() {
        A2aCardSigningService signing = mock(A2aCardSigningService.class);
        when(signing.isEnabled()).thenReturn(false);
        AuditSealA2aSigner signer = new AuditSealA2aSigner(signing, pinned(true));
        assertFalse(signer.isAvailable());
        assertTrue(signer.sign(Map.of()).isEmpty());
        verify(signing, never()).signCompactJws(any(), any());

        when(signing.isEnabled()).thenReturn(true);
        when(signing.signCompactJws(any(), any())).thenReturn(null);
        assertTrue(signer.sign(Map.of()).isEmpty());
    }

    @Test
    void isUnavailableWithoutAPinnedKeyBecauseSealsCannotBeResigned() {
        A2aCardSigningService signing = mock(A2aCardSigningService.class);
        when(signing.isEnabled()).thenReturn(true);
        AuditSealA2aSigner signer = new AuditSealA2aSigner(signing, pinned(false));

        assertFalse(signer.isAvailable());
        assertTrue(signer.sign(Map.of("seal_hash", "abc")).isEmpty());
        verify(signing, never()).signCompactJws(any(), any());
    }
}
