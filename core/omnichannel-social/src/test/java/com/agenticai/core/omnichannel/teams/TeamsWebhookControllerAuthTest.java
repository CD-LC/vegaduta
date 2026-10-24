package com.agenticai.core.omnichannel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.session.AsyncInboundReply;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The Teams webhook is authenticated by the Bot Framework JWT in {@code Authorization}. A missing,
 * invalid or wrong-audience token must be a 403 that processes nothing and claims no dedup key; a
 * request for an app id that is not the connected credential is refused before the token is even
 * looked at.
 */
class TeamsWebhookControllerAuthTest {

    private static final String APP_ID = "app-0000-1111";
    private static final String ACTIVITY =
            "{\"type\":\"message\",\"id\":\"act-1\",\"text\":\"hello\",\"serviceUrl\":\"https://smba.example/\","
                    + "\"from\":{\"id\":\"user-1\"},\"conversation\":{\"id\":\"conv-1\"}}";

    private TeamsCredentialRepository credentials;
    private TeamsBotFrameworkJwtValidator jwt;
    private ChannelSessionService sessions;
    private InboundMessageDedupService dedup;
    private TeamsWebhookController controller;
    private JsonNode activity;

    @BeforeEach
    void setUp() throws Exception {
        TenantResolutionService tenants = mock(TenantResolutionService.class);
        credentials = mock(TeamsCredentialRepository.class);
        jwt = mock(TeamsBotFrameworkJwtValidator.class);
        sessions = mock(ChannelSessionService.class);
        dedup = mock(InboundMessageDedupService.class);
        controller =
                new TeamsWebhookController(
                        tenants,
                        credentials,
                        mock(TeamsCredentialService.class),
                        jwt,
                        sessions,
                        mock(AgentChatService.class),
                        mock(HumanApprovalGateService.class),
                        mock(WorkflowEventPublisher.class),
                        mock(AuditLogService.class),
                        dedup,
                        mock(WorkflowTriggerNodeService.class),
                        mock(WorkflowTriggerPort.class),
                        new AsyncInboundReply(Runnable::run));
        when(tenants.resolveTenantSchema(ExternalAccountProvider.TEAMS, APP_ID))
                .thenReturn(Optional.of("tenant_teams"));
        TeamsCredential credential = mock(TeamsCredential.class);
        when(credential.getAppId()).thenReturn(APP_ID);
        when(credentials.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(dedup.tryClaim(eq(ChannelType.TEAMS), any())).thenReturn(true);
        activity = new ObjectMapper().readTree(ACTIVITY);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void assertForbiddenAndUntouched(ResponseEntity<Void> response) {
        assertEquals(403, response.getStatusCode().value());
        verify(dedup, never()).tryClaim(any(ChannelType.class), any());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void anInvalidBotFrameworkTokenIsForbidden() {
        when(jwt.isValid(any(), eq(APP_ID), any())).thenReturn(false);

        assertForbiddenAndUntouched(
                controller.inbound(APP_ID, activity, "Bearer forged.jwt.value"));
    }

    @Test
    void aMissingAuthorizationHeaderIsForbidden() {
        when(jwt.isValid(any(), eq(APP_ID), any())).thenReturn(false);

        assertForbiddenAndUntouched(controller.inbound(APP_ID, activity, null));
        verify(jwt).isValid(org.mockito.ArgumentMatchers.isNull(), eq(APP_ID), any());
    }

    @Test
    void aMalformedAuthorizationSchemeIsForbidden() {
        when(jwt.isValid(any(), eq(APP_ID), any())).thenReturn(false);

        assertForbiddenAndUntouched(controller.inbound(APP_ID, activity, "Basic dXNlcjpwYXNz"));
    }

    @Test
    void anAppIdThatIsNotTheConnectedCredentialIsForbiddenWithoutCheckingTheToken() {
        TeamsCredential other = mock(TeamsCredential.class);
        when(other.getAppId()).thenReturn("a-different-app");
        when(credentials.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(other));

        assertForbiddenAndUntouched(controller.inbound(APP_ID, activity, "Bearer token"));
        verifyNoInteractions(jwt);
    }
}
