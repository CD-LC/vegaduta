package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.email.dto.EmailActivationRequest;
import com.agenticai.core.omnichannel.email.dto.EmailCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link EmailCredentialController}: activate/current/disconnect over the credential service. */
class EmailCredentialControllerTest {

    private EmailCredentialService credentialService;
    private EmailCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(EmailCredentialService.class);
        controller = new EmailCredentialController(credentialService);
    }

    @Test
    void activateReturns201WithTheStoredCredentialShape() {
        EmailActivationRequest request =
                new EmailActivationRequest("imap.example.com", 993, "support@example.com", "pw", true);
        EmailCredentialResponse response =
                new EmailCredentialResponse(
                        UUID.randomUUID(), "imap.example.com", 993, "support@example.com", true,
                        EmailCredentialStatus.ACTIVE, Instant.now(), Instant.now(), null);
        when(credentialService.activate(request)).thenReturn(response);

        ResponseEntity<EmailCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertSame(response, result.getBody());
    }

    @Test
    void currentReturns200WhenConnectedAnd204WhenNot() {
        EmailCredentialResponse response =
                new EmailCredentialResponse(
                        UUID.randomUUID(), "imap.example.com", 993, "support@example.com", true,
                        EmailCredentialStatus.ACTIVE, Instant.now(), null, null);
        when(credentialService.current()).thenReturn(Optional.of(response));

        ResponseEntity<EmailCredentialResponse> ok = controller.current();
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
