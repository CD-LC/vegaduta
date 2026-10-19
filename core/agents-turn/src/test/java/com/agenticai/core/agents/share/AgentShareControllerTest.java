package com.agenticai.core.agents.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

/**
 * {@code POST /api/share/agents/{shareId}/chat/stream} through real Spring MVC return-value
 * handling. Calling the controller method directly cannot see a body that MVC refuses to stream -
 * the same blind spot {@code OpenAiCompatControllerTest}'s MockMvc tests cover for {@code
 * /v1/chat/completions}.
 */
class AgentShareControllerTest {

    private static final String SHARE_ID = "abcdefghijklmnop";

    private AgentShareService shareService;
    private AgentChatService agentChatService;
    private MockMvc mvc;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        shareService = mock(AgentShareService.class);
        agentChatService = mock(AgentChatService.class);
        AgentShareController controller =
                new AgentShareController(
                        shareService,
                        agentChatService,
                        mock(TenantLimits.class),
                        mock(ProviderModelCatalogService.class),
                        new ShareVisitorSessions("", "test-key"));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(true);
        when(shareService.resolveActiveShare(SHARE_ID))
                .thenReturn(
                        Optional.of(
                                new AgentShareService.ResolvedShare(
                                        new AgentShare(agentId, SHARE_ID), mock(Agent.class))));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private void agentReplies(Flux<String> reply) {
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() != null
                                                && r.extraTools() != null
                                                && r.turnRef() == null)))
                .thenReturn(reply);
    }

    private MvcResult stream(String json) throws Exception {
        return mvc.perform(
                        post("/api/share/agents/" + SHARE_ID + "/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andReturn();
    }

    private static List<String> dataFrames(MvcResult result) throws Exception {
        return result.getResponse()
                .getContentAsString()
                .lines()
                .filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5))
                .toList();
    }

    @Test
    void streamingOverMvcIsServerSentEvents() throws Exception {
        agentReplies(Flux.just("Hel", "lo"));

        MvcResult result =
                mvc.perform(
                                post("/api/share/agents/" + SHARE_ID + "/chat/stream")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"message\":\"hi\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        result.getAsyncResult(5000);

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(
                result.getResponse().getContentType().startsWith("text/event-stream"),
                result.getResponse().getContentType());
        assertEquals(List.of("Hel", "lo"), dataFrames(result));
    }

    @Test
    void streamIsSubscribedOnlyAfterTheTenantContextIsCleared() throws Exception {
        AtomicBoolean contextPresentAtSubscribe = new AtomicBoolean(true);
        agentReplies(
                Flux.defer(
                        () -> {
                            contextPresentAtSubscribe.set(TenantContext.current().isPresent());
                            return Flux.just("ok");
                        }));

        MvcResult result = stream("{\"message\":\"hi\"}");
        result.getAsyncResult(5000);

        assertEquals(List.of("ok"), dataFrames(result));
        assertEquals(false, contextPresentAtSubscribe.get());
    }

    @Test
    void midStreamFailureBecomesAnInBandErrorMarkerFrame() throws Exception {
        agentReplies(
                Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("boom"))));

        MvcResult result = stream("{\"message\":\"hi\"}");
        result.getAsyncResult(5000);

        List<String> frames = dataFrames(result);
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("partial", frames.get(0));
        assertTrue(frames.get(1).startsWith(AgentController.STREAM_ERROR_MARKER), frames.get(1));
    }

    @Test
    void blankMessageIs422WithoutStartingATurn() throws Exception {
        MvcResult result = stream("{\"message\":\"   \"}");

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("{\"error\":\"invalid\"}", result.getResponse().getContentAsString());
        verify(shareService, never()).resolveActiveShare(anyString());
    }

    /**
     * SEC-005 (OSS): a caller-chosen session key - here one naming ANOTHER share - is not an error,
     * it is simply not a server-signed visitor token, so it is ignored and a fresh visitor is
     * issued; the turn runs in that new visitor's session, never in the session the key names.
     */
    @Test
    void foreignSessionKeyIsIgnoredAndAFreshVisitorIsIssued() throws Exception {
        agentReplies(Flux.just("ok"));

        MvcResult result =
                stream(
                        "{\"message\":\"hi\",\"sessionKey\":\"share:zzzzzzzzzzzzzzzz:0123456789ab\"}");
        result.getAsyncResult(5000);

        assertEquals(200, result.getResponse().getStatus());
        String token = result.getResponse().getHeader(AgentController.SESSION_ID_HEADER);
        assertTrue(token != null && token.startsWith("sv1."), String.valueOf(token));
        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(agentChatService).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.inlineAttachments() != null
                                                && r.extraTools() != null
                                                && r.turnRef() == null));
        assertEquals(
                ShareVisitorSessions.chatSessionId(SHARE_ID, token.split("\\.")[1]),
                turns.getValue().sessionId());
        assertTrue(
                !turns.getValue().sessionId().contains("zzzzzzzzzzzzzzzz"),
                turns.getValue().sessionId());
    }

    @Test
    void rateLimitedTurnIs429() throws Exception {
        when(shareService.tryAcquireVoiceTurn(anyString())).thenReturn(false);

        MvcResult result = stream("{\"message\":\"hi\"}");

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("{\"error\":\"rate_limited\"}", result.getResponse().getContentAsString());
    }

    @Test
    void unknownOrRevokedShareIs410() throws Exception {
        when(shareService.resolveActiveShare(SHARE_ID)).thenReturn(Optional.empty());

        MvcResult result = stream("{\"message\":\"hi\"}");

        assertEquals(410, result.getResponse().getStatus());
        assertEquals("{\"error\":\"expired\"}", result.getResponse().getContentAsString());
    }

    /** SE-62: an id that resolves to no tenant answers exactly like a revoked one. */
    @Test
    void unknownShareIdOnMetaAndReportIs410Expired() throws Exception {
        when(shareService.resolveTenantSchema("zzzzzzzzzzzzzzzz")).thenReturn(Optional.empty());

        MvcResult meta =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/share/agents/zzzzzzzzzzzzzzzz"))
                        .andReturn();
        MvcResult report =
                mvc.perform(post("/api/share/agents/zzzzzzzzzzzzzzzz/report")).andReturn();

        assertEquals(410, meta.getResponse().getStatus());
        assertEquals("{\"error\":\"expired\"}", meta.getResponse().getContentAsString());
        assertEquals(410, report.getResponse().getStatus());
        assertEquals("{\"error\":\"expired\"}", report.getResponse().getContentAsString());
    }
}
