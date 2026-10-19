package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** {@link OpenAiCompatController}: vmcp_ grant-key callers. */
class OpenAiCompatControllerGrantKeyTest extends OpenAiCompatControllerTestSupport {

    private OpenAiCompatController grantController(java.util.Set<UUID> exposed) {
        return new OpenAiCompatController(
                agentService,
                chatService,
                new OpenAiCompatRateLimiter(noRedis(), 60, 60),
                mapper,
                16000,
                5,
                5,
                scopeOf(exposed));
    }

    private void asGrant(String subject, Runnable body) {
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of(
                        "tenant_a", subject, java.util.Set.of("ROLE_mcp-external")));
        try {
            body.run();
        } finally {
            com.agenticai.core.security.TenantContext.clear();
        }
    }

    @Test
    void grantKeyCallerRunsAnExposedAgentAsTheGrantSubjectWithoutRequireAccess() {
        agentReplies("ok");
        OpenAiCompatController c = grantController(java.util.Set.of(AGENT_ID));
        asGrant(
                "mcp-external:g1",
                () -> {
                    assertEquals(
                            200,
                            c.complete(fixtureUnchecked(false), null, null)
                                    .getStatusCode()
                                    .value());
                    org.mockito.Mockito.verify(chatService)
                            .chat(
                                    any(TurnContext.class),
                                    argThat(
                                            r ->
                                                    java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                            && r.sessionId() != null
                                                            && java.util.Objects.equals(
                                                                    r.subjectKey(),
                                                                    "mcp-external:g1")
                                                            && r.userMessage() != null
                                                            && r.attachmentKey() == null
                                                            && r.attachmentMimeType() == null
                                                            && r.inlineAttachments() == null
                                                            && r.extraTools() != null
                                                            && r.turnRef() == null
                                                            && r.visionSessionId() == null
                                                            && r.forceFastDepth() == false));
                });
        org.mockito.Mockito.verify(agentService, org.mockito.Mockito.never())
                .requireAccess(any(UUID.class), any(PermissionLevel.class));
    }

    @Test
    void grantKeyCallerCannotReachAnAgentOutsideItsGrant() {
        OpenAiCompatController c = grantController(java.util.Set.of(UUID.randomUUID()));
        asGrant(
                "mcp-external:g1",
                () -> {
                    OpenAiCompatException ex =
                            assertThrows(
                                    OpenAiCompatException.class,
                                    () -> c.complete(fixtureUnchecked(false), null, null));
                    assertEquals(404, OpenAiCompatErrors.classify(ex).status());
                    assertEquals("model_not_found", OpenAiCompatErrors.classify(ex).code());
                });
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void grantKeyModelsListsOnlyGrantedAgents() throws Exception {
        UUID other = UUID.randomUUID();
        AgentResponse granted = mock(AgentResponse.class);
        when(granted.id()).thenReturn(AGENT_ID);
        when(granted.name()).thenReturn("Granted");
        when(granted.createdAt()).thenReturn(Instant.ofEpochSecond(1L));
        when(agentService.getGrantedAgent(AGENT_ID)).thenReturn(granted);
        when(agentService.getGrantedAgent(other))
                .thenThrow(new com.agenticai.core.agents.core.AgentNotFoundException(other));
        JsonNode body =
                mapper.readTree(
                        grantController(java.util.Set.of(AGENT_ID, other)).models().getBody());
        assertEquals(1, body.get("data").size());
        assertEquals(AGENT_ID.toString(), body.get("data").get(0).get("id").asText());
        org.mockito.Mockito.verify(agentService, org.mockito.Mockito.never()).listAll();
    }

    @Test
    void callerWithNeitherPrincipalNorGrantIs401() {
        OpenAiCompatException ex =
                assertThrows(
                        OpenAiCompatException.class,
                        () -> controller.complete(fixtureUnchecked(false), null, null));
        var info = OpenAiCompatErrors.classify(ex);
        assertEquals(401, info.status());
        assertEquals("invalid_api_key", info.code());
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    void sameSessionHeaderIsScopedPerCallerAndTenant() {
        String a = OpenAiCompatController.scopedSessionId("t1", "user-1", "default");
        String b = OpenAiCompatController.scopedSessionId("t1", "user-2", "default");
        String c = OpenAiCompatController.scopedSessionId("t2", "user-1", "default");
        assertFalse(a.equals(b));
        assertFalse(a.equals(c));
        assertEquals(a, OpenAiCompatController.scopedSessionId("t1", "user-1", "default"));
        // a client cannot choose the prefix: eval:/sim: forging stays unreachable
        assertTrue(OpenAiCompatController.scopedSessionId("t1", "u", "eval:x").startsWith("oai-"));
    }

    @Test
    void streamingBudgetErrorBeforeFirstTokenThrowsSoItBecomesHttp429() {
        when(chatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null)))
                .thenReturn(
                        Flux.error(
                                new PerUserBudgetExhaustedException(
                                        "Daily text budget reached.",
                                        "text",
                                        BigDecimal.ONE,
                                        BigDecimal.ONE,
                                        3600L)));
        PerUserBudgetExhaustedException ex =
                assertThrows(
                        PerUserBudgetExhaustedException.class,
                        () -> controller.complete(fixtureUnchecked(true), null, principal));
        var info = OpenAiCompatErrors.classify(ex);
        assertEquals(429, info.status());
        assertEquals(3600L, info.retryAfterSeconds());
    }
}
