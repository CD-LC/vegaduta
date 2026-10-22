package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The OIDC trigger reads the tenant and the roles list through {@link
 * com.agenticai.core.security.JwtClaimPaths} with the shared {@code app.security.oidc.*} mapping,
 * not hard-coded Keycloak claim names. Covers the Keycloak default shape and an Entra-style flat
 * layout to prove the same endpoint serves non-Keycloak issuers.
 */
class WorkflowWebhookControllerOidcTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowService workflowService;
    private JwtDecoder jwtDecoder;
    private WorkflowWebhookController controller;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        jwtDecoder = mock(JwtDecoder.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JwtDecoder> jwtDecoderProvider = mock(ObjectProvider.class);
        when(jwtDecoderProvider.getIfAvailable()).thenReturn(jwtDecoder);
        when(jwtDecoder.decode(anyString())).thenAnswer(inv -> jwtWith(inv.getArgument(0)));

        controller =
                new WorkflowWebhookController(
                        mock(TenantResolutionService.class),
                        definitionRepositoryWithActiveWorkflow(),
                        mock(WorkflowTriggerNodeService.class),
                        workflowService,
                        com.agenticai.core.json.JsonMappers.plain(),
                        jwtDecoderProvider,
                        mock(InboundMessageDedupService.class));

        when(workflowService.run(any(), any(), anyString(), anyString()))
                .thenReturn(mock(WorkflowRunResponse.class));
    }

    private WorkflowDefinitionRepository definitionRepositoryWithActiveWorkflow() {
        WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WORKFLOW_ID);
        when(definition.getStatus()).thenReturn(WorkflowStatus.ACTIVE);
        when(repository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));
        return repository;
    }

    /** A fake decoder: the token text names the layout so each test picks its claim shape. */
    private Jwt jwtWith(String token) {
        Jwt.Builder builder = Jwt.withTokenValue(token).header("alg", "none").subject("svc-caller");
        if (token.startsWith("keycloak:")) {
            builder.claim("tenant_id", "tenant_a")
                    .claim("realm_access", Map.of("roles", List.of("workflow-trigger")));
        } else if (token.startsWith("entra:")) {
            builder.claim("organization_id", "tenant_b")
                    .claim("roles", List.of("workflow-trigger", "user"));
        } else if (token.startsWith("entra-norole:")) {
            builder.claim("organization_id", "tenant_b").claim("roles", List.of("user"));
        } else if (token.startsWith("notenant:")) {
            builder.claim("roles", List.of("workflow-trigger"));
        }
        return builder.build();
    }

    @AfterEach
    void tearDown() {
        com.agenticai.core.security.TenantContext.clear();
    }

    @Test
    void keycloakDefaultClaimsStillWork() {
        ResponseEntity<?> response =
                controller.triggerOidc(WORKFLOW_ID, "Bearer keycloak:t", Map.of());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workflowService).run(eq(WORKFLOW_ID), any(), anyString(), anyString());
    }

    @Test
    void remappedClaimsServeAnEntraShapedToken() {
        ReflectionTestUtils.setField(controller, "tenantClaim", "organization_id");
        ReflectionTestUtils.setField(controller, "rolesClaimPath", "roles");

        ResponseEntity<?> response =
                controller.triggerOidc(WORKFLOW_ID, "Bearer entra:t", Map.of());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workflowService).run(eq(WORKFLOW_ID), any(), anyString(), anyString());
    }

    @Test
    void missingTriggerRoleIsForbidden() {
        ReflectionTestUtils.setField(controller, "tenantClaim", "organization_id");
        ReflectionTestUtils.setField(controller, "rolesClaimPath", "roles");

        ResponseEntity<?> response =
                controller.triggerOidc(WORKFLOW_ID, "Bearer entra-norole:t", Map.of());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(workflowService, never()).run(any(), any(), anyString(), anyString());
    }

    @Test
    void missingTenantClaimIsForbiddenAndNamesTheConfiguredClaim() {
        ReflectionTestUtils.setField(controller, "rolesClaimPath", "roles");

        ResponseEntity<?> response =
                controller.triggerOidc(WORKFLOW_ID, "Bearer notenant:t", Map.of());
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(String.valueOf(response.getBody()).contains("tenant_id"));
        verify(workflowService, never()).run(any(), any(), anyString(), anyString());
    }
}
