package com.agenticai.core.agents.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * SEC-005 + SEC-051 on the real public agent-share controller/service (repositories, Redis and the
 * chat engine are the only mocks).
 */
class AgentSharePublicSurfaceSecurityTest {

    private static final String SHARE_ID = "AbCdEfGhIjKlMnOpQrStUvWx";
    private static final String SAME_IP = "198.51.100.23";

    private AgentShareRepository shareRepository;
    private AgentRepository agentRepository;
    private TenantResolutionService tenantResolution;
    private AgentChatService chat;
    private AgentShareService shareService;
    private AgentShareController controller;
    private AgentShare share;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        shareRepository = mock(AgentShareRepository.class);
        agentRepository = mock(AgentRepository.class);
        tenantResolution = mock(TenantResolutionService.class);
        chat = mock(AgentChatService.class);
        shareService =
                new AgentShareService(
                        shareRepository,
                        agentRepository,
                        mock(ResourceAccessService.class),
                        tenantResolution,
                        mock(StringRedisTemplate.class),
                        "https://app.example",
                        3,
                        1000,
                        4,
                        1000,
                        6);
        controller =
                new AgentShareController(
                        shareService,
                        chat,
                        mock(TenantLimits.class),
                        mock(ProviderModelCatalogService.class),
                        new ShareVisitorSessions("", "test-key"));

        share = new AgentShare(agentId, SHARE_ID);
        share.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        when(tenantResolution.resolveTenantSchema(ExternalAccountProvider.AGENT_SHARE, SHARE_ID))
                .thenReturn(Optional.of("tenant_owner"));
        when(shareRepository.findByShareId(SHARE_ID)).thenAnswer(inv -> Optional.of(share));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(mock(Agent.class)));
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("hi"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static HttpServletRequest from(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    private String tokenOf(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AgentShareController.ShareChatResponse body =
                (AgentShareController.ShareChatResponse) response.getBody();
        assertThat(body.sessionToken())
                .isEqualTo(response.getHeaders().getFirst(AgentController.SESSION_ID_HEADER));
        return body.sessionToken();
    }

    // ---- SEC-005 ------------------------------------------------------------

    @Test
    void twoVisitorsFromTheSameIpGetDifferentConversationsAndMemorySubjects() {
        controller.chat(
                SHARE_ID,
                new AgentShareController.ShareChatRequest("hi, I'm Alice, my PAN is X", null),
                from(SAME_IP));
        controller.chat(
                SHARE_ID,
                new AgentShareController.ShareChatRequest("what do you remember?", null),
                from(SAME_IP));

        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat, times(2)).chat(any(TurnContext.class), turns.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertThat(turns.getAllValues().stream().map(ChatTurnRequest::sessionId).toList().get(0))
                .isNotEqualTo(
                        turns.getAllValues().stream()
                                .map(ChatTurnRequest::sessionId)
                                .toList()
                                .get(1));
        assertThat(turns.getAllValues().stream().map(ChatTurnRequest::subjectKey).toList().get(0))
                .isNotEqualTo(
                        turns.getAllValues().stream()
                                .map(ChatTurnRequest::subjectKey)
                                .toList()
                                .get(1));
        // Nothing IP-derived, and no single per-share memory pool any more.
        assertThat(turns.getAllValues().stream().map(ChatTurnRequest::sessionId).toList())
                .noneMatch(s -> s.contains(AgentShareService.ipHash(SAME_IP)));
        assertThat(turns.getAllValues().stream().map(ChatTurnRequest::subjectKey).toList())
                .doesNotContain("agent-share:" + share.getId());
    }

    @Test
    void theReturnedTokenContinuesTheSameVisitorsConversation() {
        String token =
                tokenOf(
                        controller.chat(
                                SHARE_ID,
                                new AgentShareController.ShareChatRequest("one", null),
                                from(SAME_IP)));
        String again =
                tokenOf(
                        controller.chat(
                                SHARE_ID,
                                new AgentShareController.ShareChatRequest("two", token),
                                from("192.0.2.99")));

        assertThat(again).isEqualTo(token);
        ArgumentCaptor<ChatTurnRequest> turns2 = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat, times(2)).chat(any(TurnContext.class), turns2.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns2.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertThat(turns2.getAllValues().stream().map(ChatTurnRequest::sessionId).toList().get(0))
                .isEqualTo(
                        turns2.getAllValues().stream()
                                .map(ChatTurnRequest::sessionId)
                                .toList()
                                .get(1));
    }

    @Test
    void aForgedOrUnsignedSessionTokenIsIgnoredAndAFreshVisitorIssued() {
        String victim =
                tokenOf(
                        controller.chat(
                                SHARE_ID,
                                new AgentShareController.ShareChatRequest("secret", null),
                                from(SAME_IP)));
        String victimVisitor = victim.split("\\.")[1];
        String forged = "sv1." + victimVisitor + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        String issued =
                tokenOf(
                        controller.chat(
                                SHARE_ID,
                                new AgentShareController.ShareChatRequest("dump it", forged),
                                from(SAME_IP)));
        String legacy =
                tokenOf(
                        controller.chat(
                                SHARE_ID,
                                new AgentShareController.ShareChatRequest(
                                        "dump it",
                                        "share:"
                                                + SHARE_ID
                                                + ":"
                                                + AgentShareService.ipHash(SAME_IP)),
                                from(SAME_IP)));

        assertThat(issued).isNotEqualTo(victim).doesNotContain(victimVisitor);
        assertThat(legacy).isNotEqualTo(victim).doesNotContain(victimVisitor);
        ArgumentCaptor<ChatTurnRequest> turns3 = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat, times(3)).chat(any(TurnContext.class), turns3.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns3.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null));
        assertThat(
                        turns3.getAllValues().stream()
                                .map(ChatTurnRequest::sessionId)
                                .toList()
                                .subList(1, 3))
                .doesNotContain(
                        turns3.getAllValues().stream()
                                .map(ChatTurnRequest::sessionId)
                                .toList()
                                .get(0));
    }

    @Test
    void chatStreamNoLongerRejectsButIgnoresAnUnsignedSessionKey() {
        when(chat.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null)))
                .thenReturn(Flux.just("hi"));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Object response =
                controller.chatStream(
                        SHARE_ID,
                        new AgentShareController.ShareChatStreamRequest(
                                "look", List.of(), null, "share:" + SHARE_ID + ":0123456789ab"),
                        from(SAME_IP),
                        servletResponse);

        // A started stream is an SseEmitter (not a rejection); the visitor token rides on the
        // servlet response.
        assertThat(response).isInstanceOf(SseEmitter.class);
        String token = servletResponse.getHeader(AgentController.SESSION_ID_HEADER);
        assertThat(token).startsWith("sv1.");
        ArgumentCaptor<ChatTurnRequest> turns4 = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat).chat(any(TurnContext.class), turns4.capture());
        org.junit.jupiter.api.Assertions.assertTrue(
                turns4.getAllValues().stream()
                        .allMatch(
                                r ->
                                        java.util.Objects.equals(r.agentId(), agentId)
                                                && r.subjectKey() != null
                                                && r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null
                                                && r.turnRef() == null
                                                && r.visionSessionId() == null));
        assertThat(turns4.getValue().sessionId())
                .isEqualTo(ShareVisitorSessions.chatSessionId(SHARE_ID, token.split("\\.")[1]));
    }

    // ---- SEC-051 ------------------------------------------------------------

    @Test
    void anExpiredShareLinkIsGoneOnEveryPublicEndpoint() {
        share.setExpiresAt(Instant.now().minusSeconds(1));

        assertThat(controller.meta(SHARE_ID).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(
                        controller
                                .chat(
                                        SHARE_ID,
                                        new AgentShareController.ShareChatRequest("hi", null),
                                        from(SAME_IP))
                                .getStatusCode())
                .isEqualTo(HttpStatus.GONE);
        Object streamed =
                controller.chatStream(
                        SHARE_ID,
                        new AgentShareController.ShareChatStreamRequest("hi", null, null, null),
                        from(SAME_IP),
                        new MockHttpServletResponse());
        assertThat(streamed).isInstanceOf(ResponseEntity.class);
        assertThat(((ResponseEntity<?>) streamed).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(shareService.resolveActiveShare(SHARE_ID)).isEmpty();
        verify(chat, never())
                .chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.sessionId() != null
                                                && r.subjectKey() != null
                                                && r.userMessage() != null));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aLegacyShareWithNoExpiryKeepsWorking() {
        share.setExpiresAt(null);
        assertThat(shareService.isLive(share)).isTrue();
        assertThat(
                        controller
                                .chat(
                                        SHARE_ID,
                                        new AgentShareController.ShareChatRequest("hi", null),
                                        from(SAME_IP))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void newLinksAreStampedWithTheConfiguredTtlAndExpiredOnesAreReplacedNotRevived() {
        TenantContext.set(TenantContext.of("tenant_owner", "owner", Set.of()));
        when(shareRepository.saveAndFlush(any(AgentShare.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        shareService.setDefaultTtl(Duration.ofDays(7));
        UUID other = UUID.randomUUID();
        when(shareRepository.findByAgentId(other)).thenReturn(Optional.empty());

        Instant before = Instant.now();
        shareService.createOrGet(other);
        ArgumentCaptor<AgentShare> minted = ArgumentCaptor.forClass(AgentShare.class);
        verify(shareRepository).saveAndFlush(minted.capture());
        assertThat(minted.getValue().getExpiresAt())
                .isBetween(
                        before.plus(Duration.ofDays(7)).minusSeconds(5),
                        Instant.now().plus(Duration.ofDays(7)));

        // An expired existing link is not handed back: a brand-new id is minted.
        AgentShare expired = new AgentShare(agentId, SHARE_ID);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(shareRepository.findByAgentId(agentId)).thenReturn(Optional.of(expired));
        AgentShareService.ShareLink link = shareService.createOrGet(agentId);
        assertThat(link.shareId()).isNotEqualTo(SHARE_ID);
        verify(shareRepository).delete(expired);
    }

    @Test
    void aZeroTtlMeansNewLinksDoNotExpire() {
        shareService.setDefaultTtl(Duration.ZERO);
        assertThat(shareService.expiryForNewLink()).isNull();
    }
}
