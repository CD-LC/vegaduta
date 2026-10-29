package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.meta.dto.MetaSocialActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link MetaSocialCredentialController}: Messenger/Instagram credential lifecycle. */
class MetaSocialCredentialControllerTest {

    private MetaSocialCredentialService credentialService;
    private MetaSocialCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(MetaSocialCredentialService.class);
        controller = new MetaSocialCredentialController(credentialService);
    }

    @Test
    void activateReturns201WithTheStoredCredentialShape() {
        MetaSocialActivationRequest request =
                new MetaSocialActivationRequest("page-1", "page-token", "secret", "ig-1");
        MetaSocialCredentialResponse response =
                new MetaSocialCredentialResponse(
                        UUID.randomUUID(), "page-1", "Acme", true, "acme_ig",
                        MetaSocialCredentialStatus.ACTIVE, Instant.now(), Instant.now());
        when(credentialService.activate(request)).thenReturn(response);

        ResponseEntity<MetaSocialCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertSame(response, result.getBody());
    }

    @Test
    void currentReturns200WhenConnectedAnd204WhenNot() {
        MetaSocialCredentialResponse response =
                new MetaSocialCredentialResponse(
                        UUID.randomUUID(), "page-1", "Acme", false, null,
                        MetaSocialCredentialStatus.ACTIVE, Instant.now(), null);
        when(credentialService.current()).thenReturn(Optional.of(response));

        ResponseEntity<MetaSocialCredentialResponse> ok = controller.current();
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
