package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatPortFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.chatport.ChatPortDtos.ImportResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewResponse;
import com.agenticai.core.agents.memory.importer.MemoryImportDtos;
import com.agenticai.core.agents.memory.importer.MemoryImportService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class ChatImportServiceTest {

    private final ImportedChatRepository repository = mock(ImportedChatRepository.class);
    private final MemoryImportService memoryImport = mock(MemoryImportService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ObjectProvider<MemoryImportService> memoryProvider = mock(ObjectProvider.class);
    private final ObjectProvider<AuditLogService> auditProvider = mock(ObjectProvider.class);

    private ChatImportService service(boolean enabled, int maxStored) {
        when(memoryProvider.getIfAvailable()).thenReturn(memoryImport);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
        when(repository.save(any(ImportedChat.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ChatImportService(repository, memoryProvider, auditProvider, enabled, true, 1_000_000L, 500, 5000, 2000,
            50_000, 500_000, 20_000_000L, maxStored, 20_000, 10_000, 209_715_200L, 100, 1_000_000, 16_000_000L,
            262_144_000L, 200, 26_214_400L);
    }

    private ChatImportService service() {
        return service(true, 2000);
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, e.getStatusCode());
    }

    // ---- flag off: identity ----

    @Test
    void withTheFlagOffEveryRouteIs404AndTouchesNothing() {
        ChatImportService off = service(false, 2000);
        byte[] file = bytes(ChatPortFixtures.CHATGPT_ONE);
        UUID id = UUID.randomUUID();

        assertStatus(HttpStatus.NOT_FOUND, () -> off.preview("alice", file));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.importChats("alice", file, List.of("conv-gpt-1")));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.list("alice"));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.get("alice", id));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.delete("alice", id));
        assertStatus(HttpStatus.NOT_FOUND, () -> off.memoryCandidates("alice", List.of(id)));
        assertStatus(HttpStatus.NOT_FOUND, off::requireEnabled);

        verifyNoInteractions(repository, memoryImport, audit);
    }

    @Test
    void theFeatureProbeReportsFlagsWithoutFailingWhenOff() {
        ChatPortDtos.Features f = service(false, 2000).features();
        assertFalse(f.importEnabled());
        assertTrue(f.exportEnabled());
        assertEquals(1_000_000L, f.maxBytes());
        assertEquals(500, f.maxImportConversations());
    }

    // ---- preview ----

    @Test
    void previewListsConversationsAndWritesNothing() {
        ChatImportService svc = service();
        when(repository.countByOwnerSubject("alice")).thenReturn(7L);

        PreviewResponse p = svc.preview("alice", bytes(ChatPortFixtures.CHATGPT_ONE));

        assertEquals("chatgpt", p.source());
        assertEquals(1, p.conversations().size());
        ChatPortDtos.PreviewConversation c = p.conversations().get(0);
        assertEquals("conv-gpt-1", c.id());
        assertEquals("Trip planning", c.title());
        assertEquals(3, c.messageCount());
        assertEquals("Plan a 3-day trip to Kyoto.", c.snippet());
        assertFalse(c.alreadyImported());
        assertEquals(1, p.stats().found());
        assertEquals(1, p.stats().listed());
        assertEquals(7L, p.capacity().storedChats());
        assertEquals(2000, p.capacity().maxStoredChats());
        assertEquals(500, p.capacity().maxPerImport());
        verify(repository, never()).save(any(ImportedChat.class));
        verifyNoInteractions(memoryImport, audit);
    }

    /**
     * Prod 2026-10-05 (bug 4): POST /api/chat-import/preview with the file the app's own Export > JSON had just
     * produced answered 400 "No importable conversations were found".
     */
    @Test
    void previewAcceptsTheAppsOwnJsonExport() {
        ChatImportService svc = service();
        String file = ChatExportRenderer.render(ChatExportRenderer.Format.JSON, List.of(
            new ChatExportRenderer.Conversation("session-abc", "vegaduta", "Say hi", "e2e-agent",
                java.time.Instant.parse("2026-10-05T09:00:00Z"), null,
                List.of(
                    new ChatExportRenderer.Message("user", "Say hi in three words.", null, List.of()),
                    new ChatExportRenderer.Message("assistant", "Hi there, friend!", null, List.of())),
                false, List.of())), java.time.Instant.parse("2026-10-05T10:00:00Z"));

        PreviewResponse p = svc.preview("alice", bytes(file));

        assertEquals("vegaduta", p.source());
        assertEquals(1, p.conversations().size());
        assertEquals("session-abc", p.conversations().get(0).id());
        assertEquals("Say hi", p.conversations().get(0).title());
        assertEquals(2, p.conversations().get(0).messageCount());
        assertEquals("Say hi in three words.", p.conversations().get(0).snippet());
    }

    @Test
    void previewFlagsConversationsTheUserAlreadyImported() {
        ChatImportService svc = service();
        when(repository.findSourceIds("alice", "chatgpt")).thenReturn(List.of("conv-gpt-1"));
        assertTrue(svc.preview("alice", bytes(ChatPortFixtures.CHATGPT_ONE)).conversations().get(0).alreadyImported());
    }

    @Test
    void previewNeverEchoesASecretOrAnOverlongSnippet() {
        String json = """
            [{"uuid": "s1", "name": "My password is hunter2abc", "chat_messages": [
              {"sender": "human", "text": "My password is hunter2abc and my token follows"},
              {"sender": "assistant", "text": "ok"}]},
             {"uuid": "s2", "name": "Long", "chat_messages": [
              {"sender": "human", "text": "%s"}]}]
            """.formatted("word ".repeat(100));
        PreviewResponse p = service().preview("alice", bytes(json));

        assertEquals("[hidden]", p.conversations().get(0).title());
        assertEquals("[hidden]", p.conversations().get(0).snippet());
        String snippet = p.conversations().get(1).snippet();
        assertEquals(ChatImportService.SNIPPET_CHARS + 1, snippet.length());
        assertTrue(snippet.endsWith("…"));
    }

    @Test
    void previewOfAMixedFileReportsMixedAndRejectsAnEmptyResult() {
        String mixed = "[" + ChatPortFixtures.CHATGPT_ONE.strip().substring(1, ChatPortFixtures.CHATGPT_ONE.strip().length() - 1) + ","
            + ChatPortFixtures.CLAUDE_ONE.strip().substring(1, ChatPortFixtures.CLAUDE_ONE.strip().length() - 1) + "]";
        assertEquals("mixed", service().preview("alice", bytes(mixed)).source());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service().preview("alice", bytes("[]")));
    }

    @Test
    void anOversizedFileIs413BeforeAnyParsing() {
        ChatImportService svc = service();
        byte[] big = new byte[1_000_001];
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE, () -> svc.preview("alice", big));
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE, () -> svc.importChats("alice", big, List.of("x")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> svc.preview("alice", new byte[0]));
    }

    @Test
    void hostileInputIsA400NotAServerError() {
        ChatImportService svc = service();
        assertThrows(IllegalArgumentException.class, () -> svc.preview("alice", bytes("{{{ not json")));
        String deep = "[{\"mapping\":{\"x\":" + "[".repeat(300) + "]".repeat(300) + "}}]";
        assertThrows(IllegalArgumentException.class, () -> svc.preview("alice", bytes(deep)));
    }

    // ---- import ----

    @Test
    void importStoresSelectedChatsAsReadOnlyRecordsAndCallsNothingElse() {
        ChatImportService svc = service();

        ImportResponse r = svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS), List.of("conv-b"));

        assertEquals(1, r.imported());
        assertEquals("Second", r.chats().get(0).title());
        assertEquals("conv-b", r.chats().get(0).sourceKey());
        assertTrue(r.skipped().isEmpty());

        ArgumentCaptor<ImportedChat> saved = ArgumentCaptor.forClass(ImportedChat.class);
        verify(repository).save(saved.capture());
        ImportedChat chat = saved.getValue();
        assertEquals("alice", chat.getOwnerSubject());
        assertEquals("chatgpt", chat.getSource());
        assertEquals("conv-b", chat.getSourceId());
        assertEquals("Second", chat.getTitle());
        assertEquals(2, chat.getMessageCount());
        assertEquals(java.time.Instant.ofEpochSecond(1_700_001_000L), chat.getSourceCreatedAt()); // source time preserved
        assertNull(chat.getSourceUpdatedAt());
        assertEquals("user", chat.getMessages().get(0).get("role"));
        assertEquals("Hello B", chat.getMessages().get(0).get("content"));
        assertEquals(List.of(), chat.getMessages().get(0).get("attachments"));

        // no model / tool / memory path is reachable from an import; the only collaborator touched is the audit log
        verifyNoInteractions(memoryImport);
        verify(audit).record(anyString(), anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void importWithoutASelectionOrWithTooManyIsA400() {
        ChatImportService svc = service();
        byte[] file = bytes(ChatPortFixtures.CHATGPT_ONE);
        assertStatus(HttpStatus.BAD_REQUEST, () -> svc.importChats("alice", file, List.of()));
        assertStatus(HttpStatus.BAD_REQUEST, () -> svc.importChats("alice", file, null));
        List<String> many = java.util.stream.IntStream.range(0, 501).mapToObj(i -> "id" + i).toList();
        assertStatus(HttpStatus.BAD_REQUEST, () -> svc.importChats("alice", file, many));
        verify(repository, never()).save(any(ImportedChat.class));
    }

    @Test
    void importSkipsWhatIsAlreadyThereAndWhatDoesNotExist() {
        ChatImportService svc = service();
        when(repository.findSourceIds("alice", "chatgpt")).thenReturn(List.of("conv-a"));

        ImportResponse r = svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS),
            List.of("conv-a", "conv-b", "ghost"));

        assertEquals(1, r.imported());
        assertEquals(2, r.skipped().size());
        assertTrue(r.skipped().stream().anyMatch(s -> s.id().equals("conv-a") && s.reason().equals("duplicate")));
        assertTrue(r.skipped().stream().anyMatch(s -> s.id().equals("ghost") && s.reason().equals("not_found")));
    }

    @Test
    void aConcurrentDuplicateLosingTheUniqueIndexIsReportedNotThrown() {
        ChatImportService svc = service();
        when(repository.save(any(ImportedChat.class))).thenThrow(new DataIntegrityViolationException("uq_imported_chat_source"));
        ImportResponse r = svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1"));
        assertEquals(0, r.imported());
        assertEquals("duplicate", r.skipped().get(0).reason());
    }

    @Test
    void theStoredChatCapIsEnforcedPerUser() {
        ChatImportService svc = service(true, 1);
        when(repository.countByOwnerSubject("alice")).thenReturn(1L);
        assertStatus(HttpStatus.CONFLICT, () -> svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1")));

        when(repository.countByOwnerSubject("bob")).thenReturn(0L);
        ImportResponse r = svc.importChats("bob", bytes(ChatPortFixtures.CHATGPT_TWO_CONVERSATIONS), List.of("conv-a", "conv-b"));
        assertEquals(1, r.imported());
        assertEquals("limit_reached", r.skipped().get(0).reason());
    }

    @Test
    void theStoredBytesCapIsEnforcedPerUser() {
        ChatImportService svc = service(true, 2000);
        when(repository.sumStoredBytes("alice")).thenReturn(262_144_000L);
        assertStatus(HttpStatus.CONFLICT, () -> svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1")));
        verify(repository, never()).save(any(ImportedChat.class));

        // just under the cap: the first chat that would cross it is skipped as limit_reached, not stored
        when(repository.sumStoredBytes("bob")).thenReturn(262_144_000L - 10L);
        ImportResponse r = svc.importChats("bob", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1"));
        assertEquals(0, r.imported());
        assertEquals("limit_reached", r.skipped().get(0).reason());
    }

    @Test
    void theFreePlanGetsSmallerStorageCapsAndPaidPlansKeepTheFullOnes() {
        ChatImportService svc = service(true, 2000);
        TenantLimits limits = mock(TenantLimits.class);
        svc.setTenantLimits(limits);
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
        try {
            when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
            when(repository.countByOwnerSubject("alice")).thenReturn(200L);
            assertStatus(HttpStatus.CONFLICT, () -> svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1")));
            assertEquals(200, svc.preview("alice", bytes(ChatPortFixtures.CHATGPT_ONE)).capacity().maxStoredChats());

            when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
            assertEquals(2000, svc.preview("alice", bytes(ChatPortFixtures.CHATGPT_ONE)).capacity().maxStoredChats());
            assertEquals(1, svc.importChats("alice", bytes(ChatPortFixtures.CHATGPT_ONE), List.of("conv-gpt-1")).imported());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theSharedDemoTenantCannotUploadAndTheProbeSaysSo() {
        ChatImportService svc = service(true, 2000);
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.isSharedDemoTenant("demo")).thenReturn(true);
        svc.setTenantLimits(limits);
        TenantContext.set(TenantContext.of("demo", "visitor", Set.of()));
        try {
            byte[] file = bytes(ChatPortFixtures.CHATGPT_ONE);
            assertStatus(HttpStatus.NOT_FOUND, () -> svc.preview("visitor", file));
            assertStatus(HttpStatus.NOT_FOUND, () -> svc.importChats("visitor", file, List.of("conv-gpt-1")));
            verify(repository, never()).save(any(ImportedChat.class));
            assertFalse(svc.features().importEnabled());
        } finally {
            TenantContext.clear();
        }
    }

    // ---- read / delete: owner scoping ----

    @Test
    void anotherUsersChatIsNotFoundForRead() {
        ChatImportService svc = service();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "mallory")).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.get("mallory", id));
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.delete("mallory", id));
        verify(repository, never()).delete(any(ImportedChat.class));
    }

    @Test
    void getReturnsTheStoredTranscriptAndDeleteRemovesIt() {
        ChatImportService svc = service();
        ImportedChat stored = ChatImportService.toEntity("alice", firstParsed());
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "alice")).thenReturn(Optional.of(stored));

        ChatPortDtos.ImportedChatView v = svc.get("alice", id);
        assertEquals("Trip planning", v.title());
        assertEquals(3, v.messages().size());
        assertEquals("Plan a 3-day trip to Kyoto.", v.messages().get(0).content());
        assertEquals(List.of("image", "map.png"), v.messages().get(2).attachments());
        assertEquals(java.time.Instant.ofEpochMilli(1_700_000_001_000L), v.messages().get(0).timestamp());

        svc.delete("alice", id);
        verify(repository).delete(stored);
    }

    private static ChatImportParser.ParsedChat firstParsed() {
        return ChatImportParser.parse(bytes(ChatPortFixtures.CHATGPT_ONE), ChatImportParser.Limits.defaults(), true, null).chats().get(0);
    }

    // ---- optional memory step ----

    @Test
    void memoryCandidatesProposeFirstPersonLinesThroughTheMemoryImportPreviewOnly() {
        ChatImportService svc = service();
        UUID id = UUID.randomUUID();
        String json = """
            [{"uuid": "m1", "name": "About me", "chat_messages": [
              {"sender": "human", "text": "I live in Pune. What is the weather like? My stack is Spring and Postgres."},
              {"sender": "assistant", "text": "I live in the cloud, as a model."}]}]
            """;
        ImportedChat stored = ChatImportService.toEntity("alice", ChatImportParser.parse(bytes(json),
            ChatImportParser.Limits.defaults(), true, null).chats().get(0));
        when(repository.findByIdAndOwnerSubject(id, "alice")).thenReturn(Optional.of(stored));
        when(memoryImport.limits()).thenReturn(new MemoryImportDtos.ImportLimits(200, 20_000));
        MemoryImportDtos.PreviewResponse expected = new MemoryImportDtos.PreviewResponse(List.of(),
            new MemoryImportDtos.PreviewStats(2, 2, 0, 0), new MemoryImportDtos.Capacity(50, 25), "claude");
        when(memoryImport.preview(anyString(), any(MemoryImportDtos.PreviewRequest.class))).thenReturn(expected);

        MemoryImportDtos.PreviewResponse r = svc.memoryCandidates("alice", List.of(id));

        assertEquals(expected, r);
        ArgumentCaptor<MemoryImportDtos.PreviewRequest> req = ArgumentCaptor.forClass(MemoryImportDtos.PreviewRequest.class);
        verify(memoryImport).preview(anyString(), req.capture());
        assertEquals("claude", req.getValue().sourceHint());
        assertEquals("- I live in Pune.\n- My stack is Spring and Postgres.\n", req.getValue().text());
        // proposing is not saving: nothing was persisted or audited
        verify(repository, never()).save(any(ImportedChat.class));
        verify(memoryImport, never()).importFacts(anyString(), any(MemoryImportDtos.ImportRequest.class));
    }

    @Test
    void memoryCandidatesAreEmptyWhenNothingLooksLikeAFactAndNeedMemoryImportOn() {
        ChatImportService svc = service();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndOwnerSubject(id, "alice")).thenReturn(Optional.of(ChatImportService.toEntity("alice", firstParsed())));
        when(memoryImport.limits()).thenReturn(new MemoryImportDtos.ImportLimits(200, 20_000));

        MemoryImportDtos.PreviewResponse r = svc.memoryCandidates("alice", List.of(id));
        assertTrue(r.facts().isEmpty());
        verify(memoryImport, never()).preview(anyString(), any(MemoryImportDtos.PreviewRequest.class));

        // memory import switched off: its own limits() answers 404 and that is passed through
        when(memoryImport.limits()).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "off"));
        assertStatus(HttpStatus.NOT_FOUND, () -> svc.memoryCandidates("alice", List.of(id)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> svc.memoryCandidates("alice", List.of()));
    }

    @Test
    void mapKeysOfTheStoredShapeAreStable() {
        ImportedChat e = ChatImportService.toEntity("alice", firstParsed());
        Map<String, Object> m = e.getMessages().get(2);
        assertEquals(List.of("role", "content", "ts", "attachments"), List.copyOf(m.keySet()));
    }
}
