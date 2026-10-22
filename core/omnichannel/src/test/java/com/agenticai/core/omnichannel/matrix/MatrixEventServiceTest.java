package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.Batch;
import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.Invite;
import com.agenticai.core.omnichannel.matrix.MatrixSyncParser.RoomEvent;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundChannelExecutor;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Routing of one sync batch: policy, rooms, pairing, threads, encryption notice, invites. */
class MatrixEventServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String BOT = "@bot:hs";
    private static final String ROOM = "!room:hs";

    private FakeHomeserver server;
    private ChannelSessionService sessions;
    private AgentChatService agent;
    private HumanApprovalGateService gate;
    private InboundMessageDedupService dedup;
    private MatrixRoomNoticeRepository notices;
    private MatrixEventService service;
    private ChannelSession session;

    @BeforeEach
    void setUp() throws Exception {
        TenantContext.set(TenantContext.of("tenant_test", "test", Set.of()));
        server = new FakeHomeserver();
        server.on(
                "GET /_matrix/client/v3/rooms/!room:hs/joined_members",
                200,
                "{\"joined\":{\"@bot:hs\":{},\"@alice:hs\":{}}}");
        server.on(
                "GET /_matrix/client/v3/rooms/!group:hs/joined_members",
                200,
                "{\"joined\":{\"@bot:hs\":{},\"@alice:hs\":{},\"@carol:hs\":{}}}");
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{}");
        server.on("POST /_matrix/client/v3/rooms/", 200, "{\"room_id\":\"x\"}");

        sessions = mock(ChannelSessionService.class);
        agent = mock(AgentChatService.class);
        gate = mock(HumanApprovalGateService.class);
        dedup = mock(InboundMessageDedupService.class);
        notices = mock(MatrixRoomNoticeRepository.class);
        when(dedup.tryClaim(eq(ChannelType.MATRIX), anyString())).thenReturn(true);
        when(sessions.consumeAiDisclosurePrefix(any())).thenReturn("");
        when(agent.chat(any(), any())).thenReturn(Flux.just("po", "ng"));

        session = mock(ChannelSession.class);
        when(session.isPaired()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getAgentId()).thenReturn(UUID.randomUUID());
        when(session.getSessionId()).thenReturn("s-1");
        when(session.getExternalId()).thenReturn(ROOM);
        when(sessions.resolveOrCreate(eq(ChannelType.MATRIX), anyString(), anyString()))
                .thenReturn(session);

        service =
                new MatrixEventService(
                        sessions,
                        agent,
                        gate,
                        mock(AuditLogService.class),
                        dedup,
                        server.client(),
                        InboundChannelExecutor.synchronous(),
                        notices);
    }

    @AfterEach
    void tearDown() {
        server.close();
        TenantContext.clear();
    }

    private MatrixCredentialService.ActiveConnection connection(
            MatrixSenderMode mode, MatrixJoinPolicy join, String allowed) {
        return new MatrixCredentialService.ActiveConnection(
                server.endpoint("tok"),
                BOT,
                null,
                new MatrixSenderPolicy(BOT, mode, join, allowed));
    }

    private MatrixCredentialService.ActiveConnection defaults() {
        return connection(MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, "");
    }

    private static RoomEvent message(String room, String eventId, String sender, String contentJson)
            throws Exception {
        return new RoomEvent(
                room, eventId, sender, "m.room.message", MAPPER.readTree(contentJson), 1L);
    }

    private static Batch batch(RoomEvent... events) {
        return new Batch("next", List.of(), List.of(events), Map.of());
    }

    private JsonNode lastGatePayload() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(gate, times(1))
                .evaluate(
                        eq(MatrixChannelAdapter.ACTION_TYPE),
                        anyString(),
                        payload.capture(),
                        anyString());
        return MAPPER.readTree(payload.getValue());
    }

    @Test
    void aFailingEventDoesNotStopTheRestOfTheBatchOrMakeItRetriedForEver() throws Exception {
        when(dedup.tryClaim(ChannelType.MATRIX, "$bad"))
                .thenThrow(new IllegalStateException("redis down"));

        boolean ok =
                service.process(
                        defaults(),
                        batch(
                                message(
                                        ROOM,
                                        "$bad",
                                        "@alice:hs",
                                        "{\"msgtype\":\"m.text\",\"body\":\"one\"}"),
                                message(
                                        ROOM,
                                        "$good",
                                        "@alice:hs",
                                        "{\"msgtype\":\"m.text\",\"body\":\"two\"}")),
                        true);

        // true = the position may advance; the second event was still answered.
        assertThat(ok).isTrue();
        verify(gate, times(1))
                .evaluate(
                        eq(MatrixChannelAdapter.ACTION_TYPE),
                        anyString(),
                        anyString(),
                        anyString());
    }

    @Test
    void aPairedDirectMessageGetsTheAgentReplyThroughTheApprovalGate() throws Exception {
        boolean ok =
                service.process(
                        defaults(),
                        batch(
                                message(
                                        ROOM,
                                        "$e1",
                                        "@alice:hs",
                                        "{\"msgtype\":\"m.text\",\"body\":\"ping?\"}")),
                        true);

        assertThat(ok).isTrue();
        JsonNode payload = lastGatePayload();
        assertThat(payload.path("to").asString()).isEqualTo(ROOM);
        assertThat(payload.path("body").asString()).isEqualTo("pong");
        // A direct chat needs no quote.
        assertThat(payload.path("replyToEventId").isNull()).isTrue();
        assertThat(server.requestsTo("PUT", "/_matrix/client/v3/rooms/!room:hs/typing/"))
                .hasSize(1);
    }

    @Test
    void anUnpairedSenderGetsThePairingNoticeOnceAndNoAgentTurn() throws Exception {
        when(session.isPaired()).thenReturn(false);
        when(session.isPairingNoticeSent()).thenReturn(false);
        when(session.getPairingCode()).thenReturn("ABC123");

        service.process(
                defaults(),
                batch(
                        message(
                                ROOM,
                                "$e1",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"hi\"}")),
                true);

        JsonNode payload = lastGatePayload();
        assertThat(payload.path("body").asString()).contains("ABC123").contains("approve");
        verify(sessions).markPairingNoticeSent(session.getId());
        verify(agent, never()).chat(any(), any());
    }

    @Test
    void aSenderOutsideTheAllowListIsIgnoredWithoutASessionOrAReply() throws Exception {
        service.process(
                connection(MatrixSenderMode.ALLOWLIST, MatrixJoinPolicy.OFF, "@friend:hs"),
                batch(
                        message(
                                ROOM,
                                "$e1",
                                "@stranger:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"hi\"}")),
                true);

        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void theBotsOwnMessagesAndNoticesAreIgnored() throws Exception {
        service.process(
                defaults(),
                batch(
                        message(ROOM, "$e1", BOT, "{\"msgtype\":\"m.text\",\"body\":\"echo\"}"),
                        message(
                                ROOM,
                                "$e2",
                                "@alice:hs",
                                "{\"msgtype\":\"m.notice\",\"body\":\"other bot\"}"),
                        message(
                                ROOM,
                                "$e3",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"edit\",\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$e0\"}}")),
                true);

        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aGroupRoomIsAnsweredOnlyWhenTheBotIsMentionedAndTheReplyQuotesTheMessage()
            throws Exception {
        String group = "!group:hs";
        when(session.getExternalId()).thenReturn(group);
        service.process(
                defaults(),
                batch(
                        message(
                                group,
                                "$g1",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"just talking\"}")),
                true);
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());

        service.process(
                defaults(),
                batch(
                        message(
                                group,
                                "$g2",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"@bot: what time is it\"}")),
                true);

        JsonNode payload = lastGatePayload();
        assertThat(payload.path("replyToEventId").asString()).isEqualTo("$g2");
        assertThat(payload.path("threadRootEventId").isNull()).isTrue();
    }

    @Test
    void aMessageInAThreadIsAnsweredInThatThread() throws Exception {
        service.process(
                defaults(),
                batch(
                        message(
                                ROOM,
                                "$t1",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"in thread\",\"m.relates_to\":{\"rel_type\":\"m.thread\",\"event_id\":\"$root\"}}")),
                true);

        JsonNode payload = lastGatePayload();
        assertThat(payload.path("threadRootEventId").asString()).isEqualTo("$root");
        assertThat(payload.path("replyToEventId").asString()).isEqualTo("$t1");
    }

    @Test
    void aDuplicateEventIsANoOp() throws Exception {
        when(dedup.tryClaim(ChannelType.MATRIX, "$dup")).thenReturn(false);

        service.process(
                defaults(),
                batch(
                        message(
                                ROOM,
                                "$dup",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"hi\"}")),
                true);

        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void anEncryptedRoomGetsTheUnsupportedNoticeExactlyOnce() throws Exception {
        when(notices.recordIfAbsent(ROOM, MatrixRoomNotice.KIND_ENCRYPTED)).thenReturn(1, 0);
        RoomEvent encrypted =
                new RoomEvent(
                        ROOM, "$x1", "@alice:hs", "m.room.encrypted", MAPPER.readTree("{}"), 1L);
        RoomEvent encrypted2 =
                new RoomEvent(
                        ROOM, "$x2", "@alice:hs", "m.room.encrypted", MAPPER.readTree("{}"), 2L);

        service.process(defaults(), batch(encrypted, encrypted2), true);

        JsonNode payload = lastGatePayload();
        assertThat(payload.path("body").asString())
                .contains("end-to-end encrypted")
                .contains("not support");
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
        verify(agent, never()).chat(any(), any());
    }

    @Test
    void anEncryptedRoomIsSilentForASenderThePolicyDrops() throws Exception {
        RoomEvent encrypted =
                new RoomEvent(
                        ROOM, "$x1", "@stranger:hs", "m.room.encrypted", MAPPER.readTree("{}"), 1L);

        service.process(
                connection(MatrixSenderMode.ALLOWLIST, MatrixJoinPolicy.OFF, "@friend:hs"),
                batch(encrypted),
                true);

        verify(notices, never()).recordIfAbsent(anyString(), anyString());
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void invitesAreIgnoredByDefault() {
        Batch invites =
                new Batch("n", List.of(new Invite("!inv:hs", "@alice:hs")), List.of(), Map.of());

        service.process(
                connection(MatrixSenderMode.PAIRING, MatrixJoinPolicy.OFF, "@alice:hs"),
                invites,
                true);

        assertThat(server.requestsTo("POST", "/_matrix/client/v3/rooms/")).isEmpty();
    }

    @Test
    void anInviteFromAnAllowListedUserIsJoinedAndOneFromAStrangerIsNot() {
        Batch invites =
                new Batch(
                        "n",
                        List.of(
                                new Invite("!ok:hs", "@alice:hs"),
                                new Invite("!no:hs", "@stranger:hs")),
                        List.of(),
                        Map.of());

        service.process(
                connection(MatrixSenderMode.PAIRING, MatrixJoinPolicy.ALLOWLIST, "@alice:hs"),
                invites,
                true);

        assertThat(server.requestsTo("POST", "/_matrix/client/v3/rooms/")).hasSize(1);
        assertThat(server.requestsTo("POST", "/_matrix/client/v3/rooms/").get(0).path())
                .contains("!ok:hs/join");
    }

    @Test
    void theInitialSyncOnlyHandlesInvitesAndSkipsHistory() throws Exception {
        Batch history =
                batch(
                        message(
                                ROOM,
                                "$old",
                                "@alice:hs",
                                "{\"msgtype\":\"m.text\",\"body\":\"old\"}"));

        boolean ok = service.process(defaults(), history, false);

        assertThat(ok).isTrue();
        verify(dedup, never()).tryClaim(any(ChannelType.class), anyString());
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aFullInboundExecutorShedsTheBatchAndReleasesTheClaim() throws Exception {
        InboundChannelExecutor full = mock(InboundChannelExecutor.class); // acquire() returns null
        MatrixEventService shedding =
                new MatrixEventService(
                        sessions,
                        agent,
                        gate,
                        mock(AuditLogService.class),
                        dedup,
                        server.client(),
                        full,
                        notices);

        boolean ok =
                shedding.process(
                        defaults(),
                        batch(
                                message(
                                        ROOM,
                                        "$e1",
                                        "@alice:hs",
                                        "{\"msgtype\":\"m.text\",\"body\":\"hi\"}")),
                        true);

        assertThat(ok).isFalse();
        verify(dedup).release(ChannelType.MATRIX, "$e1");
        verify(sessions, never()).resolveOrCreate(any(), anyString(), anyString());
    }

    @Test
    void aFileMessageReachesTheAgentAsADescriptionWithSmallTextContentInline() throws Exception {
        server.on(
                "GET /_matrix/client/v1/media/download/hs/note",
                r -> new FakeHomeserver.Response(200, "text/plain", "buy milk".getBytes()));

        service.process(
                defaults(),
                batch(
                        message(
                                ROOM,
                                "$f1",
                                "@alice:hs",
                                "{\"msgtype\":\"m.file\",\"body\":\"todo.txt\",\"url\":\"mxc://hs/note\","
                                        + "\"info\":{\"mimetype\":\"text/plain\",\"size\":8}}")),
                true);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sessions).resolveOrCreate(eq(ChannelType.MATRIX), eq(ROOM), text.capture());
        assertThat(text.getValue())
                .contains("Attachment (file): todo.txt")
                .contains("untrusted data")
                .contains("buy milk");
    }

    @Test
    void aBinaryFileIsDescribedButNeverDownloaded() throws Exception {
        service.process(
                defaults(),
                batch(
                        message(
                                ROOM,
                                "$f2",
                                "@alice:hs",
                                "{\"msgtype\":\"m.image\",\"body\":\"cat.png\",\"url\":\"mxc://hs/cat\","
                                        + "\"info\":{\"mimetype\":\"image/png\",\"size\":2000000}}")),
                true);

        verify(sessions).resolveOrCreate(eq(ChannelType.MATRIX), eq(ROOM), contains("cat.png"));
        assertThat(server.requestsTo("GET", "/_matrix/client/v1/media/")).isEmpty();
    }

    @Test
    void mentionDetectionMatchesTheUserIdTheLocalpartAndMentionsButNotLookalikes()
            throws Exception {
        assertThat(
                        MatrixEventService.mentionsBot(
                                MAPPER.readTree("{\"body\":\"hey @bot:hs\"}"), BOT))
                .isTrue();
        assertThat(
                        MatrixEventService.mentionsBot(
                                MAPPER.readTree("{\"body\":\"@Bot, ping\"}"), BOT))
                .isTrue();
        assertThat(
                        MatrixEventService.mentionsBot(
                                MAPPER.readTree(
                                        "{\"body\":\"x\",\"m.mentions\":{\"user_ids\":[\"@bot:hs\"]}}"),
                                BOT))
                .isTrue();
        assertThat(
                        MatrixEventService.mentionsBot(
                                MAPPER.readTree("{\"body\":\"@bottle is here\"}"), BOT))
                .isFalse();
        assertThat(MatrixEventService.mentionsBot(MAPPER.readTree("{\"body\":\"nothing\"}"), BOT))
                .isFalse();
    }

    @Test
    void aSessionHeldByAHumanSwallowsTheMessage() throws Exception {
        var held =
                new com.agenticai.core.omnichannel.session.ConversationHeldByHumanException(
                        session);
        when(sessions.resolveOrCreate(eq(ChannelType.MATRIX), anyString(), anyString()))
                .thenThrow(held);

        boolean ok =
                service.process(
                        defaults(),
                        batch(
                                message(
                                        ROOM,
                                        "$h1",
                                        "@alice:hs",
                                        "{\"msgtype\":\"m.text\",\"body\":\"hi\"}")),
                        true);

        assertThat(ok).isTrue();
        verify(gate, never()).evaluate(anyString(), anyString(), anyString(), anyString());
    }
}
