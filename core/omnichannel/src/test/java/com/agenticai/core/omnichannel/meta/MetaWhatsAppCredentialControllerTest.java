package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.meta.dto.MetaActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link MetaWhatsAppCredentialController}: the same activate/current/disconnect shape as Twilio.
 */
class MetaWhatsAppCredentialControllerTest {

    private MetaWhatsAppCredentialService credentialService;
    private MetaWhatsAppCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(MetaWhatsAppCredentialService.class);
        controller = new MetaWhatsAppCredentialController(credentialService);
    }

    @Test
    void activateReturns201WithTheStoredCredentialShape() {
        MetaActivationRequest request =
                new MetaActivationRequest("pn-1", "waba-1", "token", "secret");
        MetaCredentialResponse response =
                new MetaCredentialResponse(
                        UUID.randomUUID(),
                        "pn-1",
                        "waba-1",
                        "+15551234567",
                        MetaWhatsAppCredentialStatus.ACTIVE,
                        Instant.now(),
                        Instant.now());
        when(credentialService.activate(request)).thenReturn(response);

        ResponseEntity<MetaCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertSame(response, result.getBody());
    }

    @Test
    void currentReturns200WhenConnectedAnd204WhenNot() {
        MetaCredentialResponse response =
                new MetaCredentialResponse(
                        UUID.randomUUID(),
                        "pn-1",
                        "waba-1",
                        "+15551234567",
                        MetaWhatsAppCredentialStatus.ACTIVE,
                        Instant.now(),
                        null);
        when(credentialService.current()).thenReturn(Optional.of(response));

        ResponseEntity<MetaCredentialResponse> ok = controller.current();
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertSame(response, ok.getBody());

        when(credentialService.current()).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NO_CONTENT, controller.current().getStatusCode());
    }

    @Test
    void disconnectIsIdempotentAndAlways204() {
        ResponseEntity<Void> result = controller.disconnect();
        verify(credentialService).disconnect();
        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
    }
}
