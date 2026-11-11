package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.twilio.dto.TwilioActivationRequest;
import com.agenticai.core.omnichannel.twilio.dto.TwilioCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link TwilioCredentialController}: the thin REST surface over {@link TwilioCredentialService}.
 */
class TwilioCredentialControllerTest {

    private TwilioCredentialService credentialService;
    private TwilioCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(TwilioCredentialService.class);
        controller = new TwilioCredentialController(credentialService);
    }

    private static TwilioCredentialResponse response() {
        return new TwilioCredentialResponse(
                UUID.randomUUID(),
                "AC1",
                null,
                false,
                "+15551234567",
                TwilioCredentialStatus.ACTIVE,
                Instant.now(),
                Instant.now());
    }

    @Test
    void activateReturns201WithTheServiceResponse() {
        TwilioActivationRequest request =
                new TwilioActivationRequest("AC1", "token", null, null, "+1555");
        TwilioCredentialResponse body = response();
        when(credentialService.activate(request)).thenReturn(body);

        ResponseEntity<TwilioCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(body, result.getBody());
    }

    @Test
    void currentReturns200WithTheRowOr204WithoutOne() {
        TwilioCredentialResponse body = response();
        when(credentialService.current()).thenReturn(Optional.of(body));
        ResponseEntity<TwilioCredentialResponse> present = controller.current();
        assertEquals(HttpStatus.OK, present.getStatusCode());
        assertEquals(body, present.getBody());

        when(credentialService.current()).thenReturn(Optional.empty());
        ResponseEntity<TwilioCredentialResponse> absent = controller.current();
        assertEquals(HttpStatus.NO_CONTENT, absent.getStatusCode());
        assertNull(absent.getBody());
    }

    @Test
    void disconnectDelegatesAndAnswers204() {
        ResponseEntity<Void> result = controller.disconnect();
        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(credentialService).disconnect();
    }
}
