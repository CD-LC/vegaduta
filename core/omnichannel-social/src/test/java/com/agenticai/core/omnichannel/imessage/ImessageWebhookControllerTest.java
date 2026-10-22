package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

class ImessageWebhookControllerTest {

    private static final String ID = "abc123webhook";
    private static final String TOKEN = "s3cret-token";

    private TenantResolutionService tenants;
    private ImessageCredentialService credentials;
    private ImessageUpdateService updates;
    private ImessageWebhookController controller;
    private final JsonNode event =
            JsonMappers.plain().createObjectNode().put("type", "new-message");

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolutionService.class);
        credentials = mock(ImessageCredentialService.class);
        updates = mock(ImessageUpdateService.class);
        controller =
                new ImessageWebhookController(
                        tenants, credentials, updates, InboundChannelExecutor.synchronous());
        when(tenants.resolveTenantSchema(ExternalAccountProvider.IMESSAGE, ID))
                .thenReturn(Optional.of("tenant_a"));
        when(credentials.webhookTokenMatches(ID, TOKEN)).thenReturn(true);
        when(updates.acceptVerified(any())).thenReturn(true);
        TenantContext.clear();
    }

    @Test
    void aValidTokenInTheQueryStringIsAcceptedAndRunsInTheTenantsContext() {
        AtomicReference<String> tenantSeen = new AtomicReference<>();
        when(updates.acceptVerified(any()))
                .thenAnswer(
                        inv -> {
                            tenantSeen.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return true;
                        });

        ResponseEntity<Void> response = controller.inbound(ID, event, TOKEN, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(tenantSeen.get()).isEqualTo("tenant_a");
        assertThat(TenantContext.current()).as("context is cleared afterwards").isEmpty();
    }

    @Test
    void theHeaderTokenWinsOverTheQueryToken() {
        ResponseEntity<Void> response = controller.inbound(ID, event, "wrong", TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(updates).acceptVerified(event);
    }

    @Test
    void aMissingTokenIsRejectedAndNothingIsProcessed() {
        assertThat(controller.inbound(ID, event, null, null).getStatusCode().value())
                .isEqualTo(403);
        verify(updates, never()).acceptVerified(any());
    }

    @Test
    void aWrongTokenIsRejectedAndNothingIsProcessed() {
        assertThat(controller.inbound(ID, event, "nope", null).getStatusCode().value())
                .isEqualTo(403);
        verify(updates, never()).acceptVerified(any());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void anUnknownWebhookIdLooksTheSameAsAWrongToken() {
        when(tenants.resolveTenantSchema(eq(ExternalAccountProvider.IMESSAGE), eq("unknown")))
                .thenReturn(Optional.empty());

        assertThat(controller.inbound("unknown", event, TOKEN, null).getStatusCode().value())
                .isEqualTo(403);
        verify(updates, never()).acceptVerified(any());
    }

    @Test
    void whenTheExecutorIsFullTheProviderIsToldToRetry() {
        when(updates.acceptVerified(any())).thenReturn(false);

        assertThat(controller.inbound(ID, event, TOKEN, null).getStatusCode().value())
                .isEqualTo(503);
    }

    @Test
    void aProcessingFailureStillAnswers200SoTheBridgeDoesNotRetryForever() {
        when(updates.acceptVerified(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(controller.inbound(ID, event, TOKEN, null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(TenantContext.current()).isEmpty();
    }
}
