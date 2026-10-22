package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.chatport.ChatExportService.ExportFile;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.InMemorySessionMemory;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@SuppressWarnings("unchecked")
class ChatExportServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    private final AgentService agentService = mock(AgentService.class);
    private final SessionMemoryService sessions = mock(SessionMemoryService.class);
    private final ImportedChatRepository repository = mock(ImportedChatRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ObjectProvider<AuditLogService> auditProvider = mock(ObjectProvider.class);
    private final Agent agent = mock(Agent.class);
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void callerIsAlice() {
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
    }

    @AfterEach
    void clearCaller() {
        TenantContext.clear();
    }

    /** SEC-004: the id chat stored alice's turns on {@code sid} under (AgentChatService#chat). */
    private String mine(String sid) {
        return SessionMemoryService.scopedSessionId("t1", agentId, "alice", sid);
    }

    private ChatExportService service(boolean exportEnabled, boolean importEnabled) {
        return service(exportEnabled, importEnabled, 20_000_000L);
    }

    private ChatExportService service(
            boolean exportEnabled, boolean importEnabled, long bulkMaxChars) {
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(agent.getName()).thenReturn("Helper Bot");
        when(agentService.requireAccess(eq(agentId), eq(PermissionLevel.RUNNER))).thenReturn(agent);
        return new ChatExportService(
                agentService,
                sessions,
                repository,
                auditProvider,
                exportEnabled,
                importEnabled,
                bulkMaxChars);
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode());
    }

    private static String text(ExportFile f) {
        return new String(f.bytes(), StandardCharsets.UTF_8);
    }

    private static List<ChatTurn> turns() {
        return List.of(
                new ChatTurn(
                        MessageRole.USER,
                        "Summarise this\n[attached: report.pdf (application/pdf)]",
                        T0),
                new ChatTurn(MessageRole.ASSISTANT, "Here is the summary.", T0.plusSeconds(5)),
                new ChatTurn(
                        MessageRole.ASSISTANT,
                        "Partial answer\n\n[reply interrupted]",
                        T0.plusSeconds(9)));
    }

    @Test
    void aFailedReplyIsExportedAsWordsNotAsTheTechnicalMarker() {
        ChatExportRenderer.Conversation c =
                ChatExportService.toConversation(
                        "s1",
                        "Helper Bot",
                        List.of(
                                new ChatTurn(MessageRole.USER, "Say ok.", T0),
                                ChatTurn.failedAssistant(
                                        "The AI provider rejected our credentials.")));

        String exported = c.messages().get(1).content();

        assertEquals("(This reply failed: The AI provider rejected our credentials.)", exported);
    }

    // ---- live session ----

    @Test
    void withTheExportFlagOffTheSessionRouteIs404AndTouchesNothing() {
        ChatExportService off = service(false, true);
        assertStatus(HttpStatus.NOT_FOUND, () -> off.exportSession(agentId, "s1", "md"));
        verifyNoInteractions(sessions, repository, audit);
        verify(agentService, never()).requireAccess(any(UUID.class), any(PermissionLevel.class));
    }

    @Test
    void anAgentTheCallerCannotRunIsNotFoundAndTheSessionIsNeverRead() {
        ChatExportService svc = service(true, false);
        when(agentService.requireAccess(eq(agentId), eq(PermissionLevel.RUNNER)))
                .thenThrow(new ResourceAccessDeniedException());
        assertThrows(
                ResourceAccessDeniedException.class, () -> svc.exportSession(agentId, "s1", "md"));
        verifyNoInteractions(sessions, audit);
    }

    @Test
    void aSessionExportsAsMarkdownWithAttachmentsListedByNameOnly() {
        ChatExportService svc = service(true, false);
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(turns());

        ExportFile f = svc.exportSession(agentId, "s1", "md");

        String md = text(f);
        assertEquals("text/markdown; charset=utf-8", f.contentType());
        assertTrue(
                f.filename().startsWith("vegaduta-chat-") && f.filename().endsWith(".md"),
                f.filename());
        assertTrue(md.startsWith("# Conversation with Helper Bot\n"), md);
        assertTrue(
                md.contains(
                        "### You · 2026-10-01 09:00 UTC\n\n"
                                + "Summarise this\n\n"
                                + "> Attachments: report.pdf\n"),
                md);
        assertFalse(md.contains("application/pdf"));
        assertTrue(md.contains("### Assistant · 2026-10-01 09:00 UTC\n\nHere is the summary."), md);
        assertTrue(md.contains("[reply interrupted]"), md);
        verify(audit)
                .record(
                        anyString(),
                        eq("CHAT_EXPORT"),
                        eq("ChatSession"),
                        eq("s1"),
                        any(),
                        anyString());
    }

    @Test
    void aSessionExportsAsVersionedJson() throws Exception {
        ChatExportService svc = service(true, false);
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(turns());

        ExportFile f = svc.exportSession(agentId, "s1", "json");

        assertEquals("application/json", f.contentType());
        JsonNode root = com.agenticai.core.json.JsonMappers.plain().readTree(f.bytes());
        assertEquals("vegaduta.chat-export", root.get("schema").asString());
        assertEquals(1, root.get("version").asInt());
        JsonNode c = root.get("conversations").get(0);
        assertEquals("s1", c.get("id").asString());
        assertEquals("vegaduta", c.get("source").asString());
        assertEquals("Helper Bot", c.get("agent").asString());
        assertEquals(3, c.get("messages").size());
        assertEquals("report.pdf", c.get("messages").get(0).get("attachments").get(0).asString());
        assertEquals("Summarise this", c.get("messages").get(0).get("content").asString());
    }

    @Test
    void aSessionExportsAsPlainText() {
        ChatExportService svc = service(true, false);
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(turns());
        ExportFile f = svc.exportSession(agentId, "s1", "txt");
        assertEquals("text/plain; charset=utf-8", f.contentType());
        assertTrue(
                text(f).contains(
                                "You [2026-10-01 09:00 UTC]:\n"
                                        + "Summarise this\n"
                                        + "(Attachments: report.pdf)\n"),
                text(f));
    }

    @Test
    void anEmptyOrExpiredSessionIs404AndABadFormatOrIdIsA400() {
        ChatExportService svc = service(true, false);
        when(sessions.isOwnedBy(mine("gone"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("gone"))).thenReturn(List.of());
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "gone", "md"));
        assertThrows(IllegalArgumentException.class, () -> svc.exportSession(agentId, "s1", "pdf"));
        assertThrows(IllegalArgumentException.class, () -> svc.exportSession(agentId, " ", "md"));
        assertThrows(
                IllegalArgumentException.class, () -> svc.exportSession(agentId, "bad\nid", "md"));
        assertThrows(
                IllegalArgumentException.class,
                () -> svc.exportSession(agentId, "x".repeat(201), "md"));
    }

    @Test
    void aSessionAtTheStoreCapCarriesATrimNote() {
        List<ChatTurn> many = new ArrayList<>();
        for (int i = 0; i < SessionMemoryService.MAX_STORED_ENTRIES; i++) {
            many.add(
                    new ChatTurn(
                            i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, "m" + i, T0));
        }
        ChatExportRenderer.Conversation c = ChatExportService.toConversation("s", "A", many);
        assertEquals(1, c.notes().size());
        assertTrue(c.notes().get(0).contains("most recent"));
        assertTrue(ChatExportService.toConversation("s", null, turns()).notes().isEmpty());
        assertEquals("Conversation", ChatExportService.toConversation("s", " ", turns()).title());
    }

    @Test
    void aSessionIsReadableOnlyByTheTenantAndUserThatStartedIt() {
        ChatExportService svc = service(true, false);
        // The history is there; only the owner record decides. Alice in t1 owns it, nobody else
        // does.
        when(sessions.isOwnedBy(mine("s1"), "t1", "alice")).thenReturn(true);
        when(sessions.getFullHistory(mine("s1"))).thenReturn(turns());

        TenantContext.set(TenantContext.of("t2", "alice", Set.of()));
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "s1", "md"));

        TenantContext.set(TenantContext.of("t1", "mallory", Set.of()));
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "s1", "json"));

        verify(sessions, never()).getFullHistory(anyString());
        verifyNoInteractions(audit);

        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
        assertTrue(
                text(svc.exportSession(agentId, "s1", "md"))
                        .startsWith("# Conversation with Helper Bot"));
    }

    @Test
    void aSessionWithNoOwnerRecordOrNoCallerContextIsNotExportable() {
        ChatExportService svc = service(true, false);
        when(sessions.getFullHistory(mine("old"))).thenReturn(turns());
        // no stub for isOwnedBy: a mock answers false, exactly like a session started before owner
        // records existed
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "old", "md"));

        TenantContext.clear();
        when(sessions.isOwnedBy(anyString(), anyString(), anyString())).thenReturn(true);
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "old", "md"));
        verify(sessions, never()).getFullHistory(anyString());
    }

    // ---- imported chats ----

    private static ImportedChat imported(String title, String content) {
        return new ImportedChat(
                "alice",
                "chatgpt",
                "id-" + title,
                title,
                T0,
                null,
                2,
                false,
                List.of(
                        java.util.Map.<String, Object>of(
                                "role",
                                "user",
                                "content",
                                content,
                                "ts",
                                T0.toString(),
                                "attachments",
                                List.of("a.png")),
                        java.util.Map.<String, Object>of(
                                "role",
                                "assistant",
                                "content",
                                "ok",
                                "ts",
                                T0.plusSeconds(1).toString(),
                                "attachments",
                                List.of())));
    }

    @Test
    void importedChatExportsFollowTheImportFlagAndOwnerScoping() {
        ChatExportService off = service(true, false);
        UUID id = UUID.randomUUID();
        assertStatus(HttpStatus.NOT_FOUND, () -> off.exportImportedChat("alice", id, "md"));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.exportAllImported("alice", "md"));
        verifyNoInteractions(repository);

        ChatExportService on = service(false, true);
        when(repository.findByIdAndOwnerSubject(id, "mallory")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> on.exportImportedChat("mallory", id, "md"));

        when(repository.findByIdAndOwnerSubject(id, "alice"))
                .thenReturn(Optional.of(imported("Trip", "hello there")));
        ExportFile f = on.exportImportedChat("alice", id, "md");
        assertTrue(text(f).startsWith("# Trip\n"), text(f));
        assertTrue(text(f).contains("Source: ChatGPT"));
        assertTrue(text(f).contains("> Attachments: a.png"));
        assertTrue(f.filename().startsWith("Trip-") && f.filename().endsWith(".md"), f.filename());
    }

    @Test
    void exportAllListsEveryImportedChatInOneFile() throws Exception {
        ChatExportService svc = service(false, true);
        when(repository.countByOwnerSubject("alice")).thenReturn(2L);
        when(repository.findFullByOwnerSubjectOrderByImportedAtDesc(
                        eq("alice"), any(Pageable.class)))
                .thenReturn(List.of(imported("One", "first"), imported("Two", "second")))
                .thenReturn(List.of());

        ExportFile f = svc.exportAllImported("alice", "json");

        JsonNode root = com.agenticai.core.json.JsonMappers.plain().readTree(f.bytes());
        assertEquals(2, root.get("conversations").size());
        assertEquals("One", root.get("conversations").get(0).get("title").asString());
        assertEquals("Two", root.get("conversations").get(1).get("title").asString());
        assertEquals(0, root.get("conversations").get(0).get("notes").size());
        assertTrue(f.filename().startsWith("vegaduta-imported-chats-"));
    }

    @Test
    void exportAllStopsAtTheSizeCapAndSaysSo() throws Exception {
        ChatExportService svc = service(false, true, 1000L);
        when(repository.countByOwnerSubject("alice")).thenReturn(3L);
        String big = "x".repeat(900);
        when(repository.findFullByOwnerSubjectOrderByImportedAtDesc(
                        eq("alice"), any(Pageable.class)))
                .thenReturn(
                        List.of(
                                imported("One", big),
                                imported("Two", big),
                                imported("Three", big)));

        ExportFile f = svc.exportAllImported("alice", "json");

        JsonNode root = com.agenticai.core.json.JsonMappers.plain().readTree(f.bytes());
        assertEquals(1, root.get("conversations").size());
        String note = root.get("conversations").get(0).get("notes").get(0).asString();
        assertTrue(note.contains("stopped after 1 of 3"), note);
    }

    @Test
    void exportAllWithNothingImportedIsAnEmptyButValidFile() throws Exception {
        ChatExportService svc = service(false, true);
        when(repository.findFullByOwnerSubjectOrderByImportedAtDesc(
                        eq("alice"), any(Pageable.class)))
                .thenReturn(List.of());
        JsonNode root =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(svc.exportAllImported("alice", "json").bytes());
        assertEquals(0, root.get("conversations").size());
        assertEquals("vegaduta.chat-export", root.get("schema").asString());
    }

    @Test
    void importThenExportRoundTripKeepsTheTranscript() {
        ChatImportParser.ParsedChat parsed =
                ChatImportParser.parse(
                                bytes(ChatPortFixtures.CLAUDE_ONE),
                                ChatImportParser.Limits.defaults(),
                                true,
                                null)
                        .chats()
                        .get(0);
        ChatExportRenderer.Conversation c =
                ChatExportService.toConversation(ChatImportService.toEntity("alice", parsed));
        assertEquals("c-claude-1", c.id());
        assertEquals("claude", c.source());
        assertEquals(2, c.messages().size());
        assertEquals(List.of("resume.pdf", "photo.png"), c.messages().get(0).attachments());
        assertEquals("Can you review my resume?", c.messages().get(0).content());
    }

    // ---- against the real SEC-004 key derivation, not a mock that accepts any id ----

    private ChatExportService realService(InMemorySessionMemory redis) {
        service(true, false); // stubs the agent, audit and RUNNER access
        return new ChatExportService(
                agentService, redis.service(), repository, auditProvider, true, false, 20_000_000L);
    }

    /** What {@code AgentChatService#chat} does for an authenticated caller's turn. */
    private void chatAs(InMemorySessionMemory redis, String subject, String sid, String text) {
        TenantContext.set(TenantContext.of("t1", subject, Set.of()));
        redis.service()
                .appendTurn(
                        SessionMemoryService.scopedSessionId("t1", agentId, subject, sid),
                        ChatTurn.user(text));
    }

    @Test
    void theOwnerExportsTheSessionTheyChattedIn() {
        InMemorySessionMemory redis = new InMemorySessionMemory();
        chatAs(redis, "alice", "s1", "Hello from alice");
        ChatExportService svc = realService(redis);

        String md = text(svc.exportSession(agentId, "s1", "md"));

        assertTrue(md.contains("Hello from alice"));
        verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void anotherMemberOfTheTenantCannotExportItWithTheSameClientId() {
        InMemorySessionMemory redis = new InMemorySessionMemory();
        chatAs(redis, "alice", "s1", "Hello from alice");
        ChatExportService svc = realService(redis);
        TenantContext.set(TenantContext.of("t1", "mallory", Set.of()));

        assertStatus(HttpStatus.NOT_FOUND, () -> svc.exportSession(agentId, "s1", "md"));
        verifyNoInteractions(audit);
    }
}
