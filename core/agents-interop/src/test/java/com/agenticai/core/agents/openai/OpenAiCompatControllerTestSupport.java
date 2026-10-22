package com.agenticai.core.agents.openai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.io.InputStream;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the {@link OpenAiCompatController} tests. Split from the former
 * OpenAiCompatControllerTest.
 */
abstract class OpenAiCompatControllerTestSupport {

    static final UUID AGENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();

    AgentService agentService;

    AgentChatService chatService;

    OpenAiCompatController controller;

    Principal principal;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentService = mock(AgentService.class);
        chatService = mock(AgentChatService.class);
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(AGENT_ID);
        when(agentService.requireAccess(any(UUID.class), eq(PermissionLevel.RUNNER)))
                .thenReturn(agent);
        ObjectProvider<StringRedisTemplate> noRedis = mock(ObjectProvider.class);
        when(noRedis.getIfAvailable()).thenReturn(null);
        controller =
                new OpenAiCompatController(
                        agentService,
                        chatService,
                        new OpenAiCompatRateLimiter(noRedis, 60, 60),
                        mapper,
                        16000,
                        5,
                        5,
                        noScope());
        principal = () -> "user-1";
    }

    OpenAiChatRequest fixture(boolean stream) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openai-compat/sdk-request.json")) {
            JsonNode node = mapper.readTree(in);
            ((tools.jackson.databind.node.ObjectNode) node).put("stream", stream);
            return mapper.treeToValue(node, OpenAiChatRequest.class);
        }
    }

    void agentReplies(String... tokens) {
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
                .thenReturn(Flux.just(tokens));
    }

    // The two tests below go through real Spring MVC return-value handling. Calling the
    // controller method directly cannot see a body that MVC refuses to stream.
    org.springframework.test.web.servlet.MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        controller)
                .setControllerAdvice(new OpenAiCompatAdvice(mapper))
                .build();
    }

    String body(boolean stream) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/openai-compat/sdk-request.json")) {
            JsonNode node = mapper.readTree(in);
            ((tools.jackson.databind.node.ObjectNode) node).put("stream", stream);
            return node.toString();
        }
    }

    // ---- vmcp_ grant-key callers ----

    ObjectProvider<OpenAiCompatCallerScope> noScope() {
        return scopeOf(null);
    }

    @SuppressWarnings("unchecked")
    ObjectProvider<OpenAiCompatCallerScope> scopeOf(java.util.Set<UUID> exposed) {
        ObjectProvider<OpenAiCompatCallerScope> p = mock(ObjectProvider.class);
        OpenAiCompatCallerScope scope =
                exposed == null ? null : () -> java.util.Optional.of(exposed);
        when(p.getIfAvailable()).thenReturn(scope);
        return p;
    }

    @SuppressWarnings("unchecked")
    ObjectProvider<StringRedisTemplate> noRedis() {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    OpenAiChatRequest fixtureUnchecked(boolean stream) {
        try {
            return fixture(stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
