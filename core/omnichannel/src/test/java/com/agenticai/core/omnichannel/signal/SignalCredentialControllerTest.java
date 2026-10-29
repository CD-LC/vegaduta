package com.agenticai.core.omnichannel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link SignalCredentialController}: credential lifecycle plus the policy update endpoint. */
class SignalCredentialControllerTest {

    private SignalCredentialService credentialService;
    private SignalCredentialController controller;

    @BeforeEach
    void setUp() {
        credentialService = mock(SignalCredentialService.class);
        controller = new SignalCredentialController(credentialService);
    }

    private static SignalCredentialResponse response() {
        return new SignalCredentialResponse(
                UUID.randomUUID(), "http://signal-cli:8080", "+4915112345678", true,
                UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE,
                SignalCredentialStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @Test
    void activateReturns201WithTheStoredCredentialShape() {
        SignalActivationRequest request =
                new SignalActivationRequest(
                        "http://signal-cli:8080", "+4915112345678", "tok",
                        UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        SignalCredentialResponse response = response();
        when(credentialService.activate(request)).thenReturn(response);

        ResponseEntity<SignalCredentialResponse> result = controller.activate(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertSame(response, result.getBody());
    }

    @Test
    void currentReturns200WhenConnectedAnd204WhenNot() {
        SignalCredentialResponse response = response();
        when(credentialService.current()).thenReturn(Optional.of(response));

        ResponseEntity<SignalCredentialResponse> ok = controller.current();
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertSame(response, ok.getBody());

        when(credentialService.current()).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NO_CONTENT, controller.current().getStatusCode());
    }

    @Test
    void updatePolicyForwardsToTheService() {
        SignalPolicyRequest request =
                new SignalPolicyRequest(UnknownSenderPolicy.PAIR, SignalGroupMode.MENTION);
        SignalCredentialResponse response = response();
        when(credentialService.updatePolicy(request)).thenReturn(response);

        assertSame(response, controller.updatePolicy(request));
    }

    @Test
    void disconnectIsIdempotentAndAlways204() {
        ResponseEntity<Void> result = controller.disconnect();
        verify(credentialService).disconnect();
        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
    }
}
