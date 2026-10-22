package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.openai.OpenAiCompatAdvice;
import com.agenticai.core.agents.openai.OpenAiCompatCallerScope;
import com.agenticai.core.agents.openai.OpenAiCompatController;
import com.agenticai.core.agents.openai.OpenAiCompatRateLimiter;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.AccessDeniedEventListener;
import com.agenticai.core.security.SecurityConfig;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.servlet.Filter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real {@code SecurityConfig.filterChain} (all three chains), the real {@link
 * McpApiKeyAuthFilter} registered for {@code /v1/*} exactly as {@link OpenAiCompatKeyAuthConfig}
 * does, and the real {@link OpenAiCompatController} + advice, driven through MockMvc. Only the
 * grant lookup, the agent service and the chat service are mocks. This is the wiring the unit tests
 * cannot see: that a {@code Bearer vmcp_} request gets past the {@code /v1/**} authorization rule
 * and the custom bearer resolver, reaches the key filter, and is served (or 404/429'd) according to
 * its grant.
 */
class OpenAiCompatSecurityChainTest {

    private static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String KEY = "vmcp_raw";

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
    private final McpServerAccessGrantService grantService =
            mock(McpServerAccessGrantService.class);
    private final McpServerRateLimiter rateLimiter = mock(McpServerRateLimiter.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentChatService chatService = mock(AgentChatService.class);
    private AnnotationConfigWebApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
        TenantContext.clear();
        McpCallerContextTestAccess.clear();
        SecurityContextHolder.clearContext();
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class Cfg {
        static OpenAiCompatController controller;
        static ObjectMapper mapper;

        @Bean
        List<AccessDeniedEventListener> accessDeniedListeners() {
            return List.of();
        }

        @Bean
        OpenAiCompatController controller() {
            return controller;
        }

        @Bean
        OpenAiCompatAdvice advice() {
            return new OpenAiCompatAdvice(mapper);
        }
    }

    @SuppressWarnings("unchecked")
    private MockMvc mvc(boolean flagOn) {
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        ObjectProvider<OpenAiCompatCallerScope> scopeProvider = mock(ObjectProvider.class);
        // The same seam OpenAiCompatKeyAuthConfig publishes; absent when the flag is off.
        when(scopeProvider.getIfAvailable())
                .thenReturn(
                        flagOn ? new OpenAiCompatKeyAuthConfig().openAiCompatCallerScope() : null);
        Cfg.controller =
                new OpenAiCompatController(
                        agentService,
                        chatService,
                        new OpenAiCompatRateLimiter(noRedis, 60, 60),
                        mapper,
                        16000,
                        5,
                        5,
                        scopeProvider);
        Cfg.mapper = mapper;

        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of(
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                                "https://auth.example.test/realms/agentic-ai",
                                        "app.keycloak.jwk-set-uri",
                                                "https://auth.example.test/realms/agentic-ai/certs",
                                        "app.openai-compat.enabled", Boolean.toString(flagOn))));
        ctx.register(Cfg.class);
        ctx.refresh();

        var builder =
                MockMvcBuilders.webAppContextSetup(ctx)
                        .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class));
        if (flagOn) {
            // Same registration OpenAiCompatKeyAuthConfig makes: after Spring Security, /v1/* only.
            builder.addFilter(
                    new McpApiKeyAuthFilter(grantService, rateLimiter, true, false), "/v1/*");
        }
        return builder.build();
    }

    private UUID stubValidKey(UUID grantId, Set<UUID> exposedAgents) {
        McpServerAccessGrant grant =
                new McpServerAccessGrant(
                        "sdk", McpApiKeyHasher.sha256Hex(KEY), "rawrawra", "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", grantId);
        when(grantService.resolveTenantSchema(KEY)).thenReturn(Optional.of("tenant_alpha"));
        when(grantService.findByRawKeyWithinCurrentTenant(KEY)).thenReturn(Optional.of(grant));
        when(rateLimiter.allow(grantId)).thenReturn(true);
        when(grantService.parseExposedAgentIds(grant)).thenReturn(exposedAgents);
        when(grantService.parseExposedWorkflowIds(grant)).thenReturn(Set.of());
        when(grantService.parseExposedKnowledgeCollectionIds(grant)).thenReturn(Set.of());
        return grantId;
    }

    private void chatReplies(Flux<String> reply) {
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
                .thenReturn(reply);
    }

    private static String body(String model, boolean stream) {
        return "{\"model\":\""
                + model
                + "\",\"stream\":"
                + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String bearer, String json) {
        var req =
                MockMvcRequestBuilders.post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json);
        return bearer == null ? req : req.header("Authorization", "Bearer " + bearer);
    }

    /**
     * A request already authenticated as a normal (non-JWT-decoder) principal, as the JWT filter
     * would leave it.
     */
    private static RequestPostProcessor authenticatedAs(String user) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_user"))));
            new RequestAttributeSecurityContextRepository().saveContext(context, request, null);
            return request;
        };
    }

    private JsonNode error(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("error");
    }

    // ---- flag ON ---------------------------------------------------------------------------

    @Test
    void flagOn_grantKeyReachesTheKeyFilterAndIsServedAsTheGrantSubject() throws Exception {
        UUID grantId = stubValidKey(UUID.randomUUID(), Set.of(AGENT_ID));
        chatReplies(Flux.just("12"));
        MockMvc mvc = mvc(true);

        MvcResult result = mvc.perform(post(KEY, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(
                "12",
                mapper.readTree(result.getResponse().getContentAsString())
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asString());
        verify(chatService)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "mcp-external:" + grantId)
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null
                                                && r.forceFastDepth() == false));
        verify(agentService, never()).requireAccess(any(UUID.class), any(PermissionLevel.class));
    }

    @Test
    void flagOn_grantKeyOutsideItsGrantGets404ModelNotFound() throws Exception {
        stubValidKey(UUID.randomUUID(), Set.of(UUID.randomUUID()));
        MvcResult result =
                mvc(true).perform(post(KEY, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("model_not_found", error(result).get("code").asString());
        verifyNoInteractions(chatService);
    }

    @Test
    void flagOn_streamingSurvivesTheAsyncDispatchReAuthorization() throws Exception {
        stubValidKey(UUID.randomUUID(), Set.of(AGENT_ID));
        chatReplies(Flux.just("12", " (4 x 3)"));
        MockMvc mvc = mvc(true);

        MvcResult started =
                mvc.perform(post(KEY, body(AGENT_ID.toString(), true)))
                        .andExpect(
                                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                        .request()
                                        .asyncStarted())
                        .andReturn();
        started.getAsyncResult(5000);
        // The container re-dispatches through the whole chain; the bearer is still present, so the
        // /v1/** rule must admit it again (anonymous authentication on the dispatch).
        MvcResult dispatched = mvc.perform(asyncDispatch(started)).andReturn();

        assertEquals(200, dispatched.getResponse().getStatus());
        assertTrue(
                started.getResponse().getContentType().startsWith("text/event-stream"),
                started.getResponse().getContentType());
        assertTrue(started.getResponse().getContentAsString().contains("[DONE]"));
    }

    @Test
    void flagOn_grantCallerOverBudgetGets429InsufficientQuota() throws Exception {
        UUID grantId = stubValidKey(UUID.randomUUID(), Set.of(AGENT_ID));
        chatReplies(
                Flux.error(
                        new PerUserBudgetExhaustedException(
                                "Daily text budget reached.",
                                "text",
                                BigDecimal.ONE,
                                BigDecimal.ONE,
                                3600L)));

        MvcResult result =
                mvc(true).perform(post(KEY, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("insufficient_quota", error(result).get("code").asString());
        assertEquals("3600", result.getResponse().getHeader("Retry-After"));
        // The budget was charged against the grant's own subject, not a shared one.
        verify(chatService)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "mcp-external:" + grantId)
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null
                                                && r.forceFastDepth() == false));
    }

    @Test
    void flagOn_unknownGrantKeyIsOpenAiShaped401FromTheKeyFilter() throws Exception {
        when(grantService.resolveTenantSchema("vmcp_nope")).thenReturn(Optional.empty());
        MvcResult result =
                mvc(true).perform(post("vmcp_nope", body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("invalid_api_key", error(result).get("code").asString());
        verifyNoInteractions(chatService);
    }

    @Test
    void flagOn_noAuthorizationHeaderGetsTheOpenAiEnvelopeNotSpringsDefaultBody() throws Exception {
        MvcResult result =
                mvc(true).perform(post(null, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertEquals("invalid_api_key", error(result).get("code").asString());
        assertTrue(
                error(result).has("message")
                        && error(result).has("type")
                        && error(result).has("param"));
        verifyNoInteractions(chatService, grantService);
    }

    @Test
    void flagOn_aMalformedNonKeyBearerStillGoesToTheJwtDecoderAndIs401() throws Exception {
        MvcResult result =
                mvc(true).perform(post("not.a.jwt", body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        verifyNoInteractions(chatService, grantService);
    }

    @Test
    void flagOn_anAuthenticatedJwtStylePrincipalPassesThroughTheKeyFilterUntouched()
            throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(agent);
        chatReplies(Flux.just("ok"));

        MvcResult result =
                mvc(true)
                        .perform(
                                post(null, body(AGENT_ID.toString(), false))
                                        .with(authenticatedAs("user-1")))
                        .andReturn();

        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        verifyNoInteractions(grantService);
        verify(chatService)
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), AGENT_ID)
                                                && r.sessionId() != null
                                                && java.util.Objects.equals(
                                                        r.subjectKey(), "user-1")
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() == null
                                                && r.extraTools() != null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null
                                                && r.forceFastDepth() == false));
    }

    // ---- flag OFF: the /v1/** rule must be exactly authenticated() --------------------------

    @Test
    void flagOff_aGrantKeyBearerIsStill401AndNeverReachesAnything() throws Exception {
        MvcResult result =
                mvc(false).perform(post(KEY, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        verifyNoInteractions(chatService, grantService);
        // Stock Spring Security 401, not the OpenAI envelope: with the flag off nothing is
        // OpenAI-shaped.
        assertFalse(result.getResponse().getContentAsString().contains("invalid_api_key"));
    }

    @Test
    void flagOff_noAuthorizationHeaderIs401WithTheStockBearerChallenge() throws Exception {
        MvcResult result =
                mvc(false).perform(post(null, body(AGENT_ID.toString(), false))).andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertFalse(result.getResponse().getContentAsString().contains("invalid_api_key"));
        assertTrue(result.getResponse().getHeader("WWW-Authenticate").startsWith("Bearer"));
    }

    @Test
    void flagOff_anAuthenticatedPrincipalIsAdmittedExactlyAsAnyOtherAuthenticatedRoute()
            throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(AGENT_ID, PermissionLevel.RUNNER)).thenReturn(agent);
        chatReplies(Flux.just("ok"));

        MvcResult result =
                mvc(false)
                        .perform(
                                post(null, body(AGENT_ID.toString(), false))
                                        .with(authenticatedAs("user-1")))
                        .andReturn();

        assertEquals(
                200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }
}
