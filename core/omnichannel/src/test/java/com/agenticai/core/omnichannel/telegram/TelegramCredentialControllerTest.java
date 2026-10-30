package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.telegram.dto.TelegramActivationRequest;
import com.agenticai.core.omnichannel.telegram.dto.TelegramCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link TelegramCredentialController}: activate/current/disconnect over the credential service.
 */
class TelegramCredentialControllerTest {

    private TelegramCredentialService credentialService;
    private TelegramCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(TelegramCredentialService.class);
        controller = new TelegramCredentialController(credentialService);
    }

    @Test
    void activateReturns201WithTheStoredCredentialShape() {
        TelegramActivationRequest request = new TelegramActivationRequest("bot-token");
        TelegramCredentialResponse response =
                new TelegramCredentialResponse(
                        UUID.randomUUID(),
                        "12",
                        "help_bot",
                        TelegramCredentialStatus.ACTIVE,
                        Instant.now(),
                        Instant.now());
        when(credentialService.activate(request)).thenReturn(response);

        ResponseEntity<TelegramCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertSame(response, result.getBody());
    }

    @Test
    void currentReturns200WhenConnectedAnd204WhenNot() {
        TelegramCredentialResponse response =
                new TelegramCredentialResponse(
                        UUID.randomUUID(),
                        "12",
                        "help_bot",
                        TelegramCredentialStatus.ACTIVE,
                        Instant.now(),
                        null);
        when(credentialService.current()).thenReturn(Optional.of(response));

        ResponseEntity<TelegramCredentialResponse> ok = controller.current();
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
