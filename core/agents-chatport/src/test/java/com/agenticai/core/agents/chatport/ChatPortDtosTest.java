package com.agenticai.core.agents.chatport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.chatport.ChatPortDtos.Capacity;
import com.agenticai.core.agents.chatport.ChatPortDtos.Features;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedChatView;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedMessage;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedRef;
import com.agenticai.core.agents.chatport.ChatPortDtos.ImportedSummary;
import com.agenticai.core.agents.chatport.ChatPortDtos.JsonUpload;
import com.agenticai.core.agents.chatport.ChatPortDtos.MemoryCandidatesRequest;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewConversation;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewResponse;
import com.agenticai.core.agents.chatport.ChatPortDtos.PreviewStats;
import com.agenticai.core.agents.chatport.ChatPortDtos.SkippedChat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatPortDtos} is the wire contract of {@code /api/chat-import}: records whose component
 * names the web reads. These tests pin the accessor names, so a "harmless" component rename is a
 * compile break here before it is a runtime bug in the console.
 */
class ChatPortDtosTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void featureAndUploadRecordsCarryTheirComponents() {
        Features f = new Features(true, false, 1_000_000L, 500);
        assertTrue(f.importEnabled());
        assertEquals(false, f.exportEnabled());
        assertEquals(1_000_000L, f.maxBytes());
        assertEquals(500, f.maxImportConversations());

        JsonUpload up = new JsonUpload("[{}]", List.of("a", "b"));
        assertEquals("[{}]", up.text());
        assertEquals(List.of("a", "b"), up.ids());

        MemoryCandidatesRequest req = new MemoryCandidatesRequest(List.of(UUID.randomUUID()));
        assertEquals(1, req.chatIds().size());
    }

    @Test
    void previewRecordsCarryTheStatsTheCardShows() {
        PreviewConversation pc =
                new PreviewConversation(
                        "conv-1", "chatgpt", "Title", NOW, 7, "snippet", true, false);
        assertEquals("conv-1", pc.id());
        assertEquals("chatgpt", pc.source());
        assertEquals("Title", pc.title());
        assertEquals(NOW, pc.createdAt());
        assertEquals(7, pc.messageCount());
        assertEquals("snippet", pc.snippet());
        assertTrue(pc.alreadyImported());
        assertEquals(false, pc.truncated());

        PreviewStats stats = new PreviewStats(10, 8, 2, true);
        assertEquals(10, stats.found());
        assertEquals(8, stats.listed());
        assertEquals(2, stats.skipped());
        assertTrue(stats.scanCapReached());

        Capacity cap = new Capacity(3L, 500, 5);
        assertEquals(3L, cap.storedChats());
        assertEquals(500, cap.maxStoredChats());
        assertEquals(5, cap.maxPerImport());

        PreviewResponse pr = new PreviewResponse("chatgpt", List.of(pc), stats, cap, List.of("w"));
        assertEquals("chatgpt", pr.source());
        assertEquals(1, pr.conversations().size());
        assertEquals(stats, pr.stats());
        assertEquals(cap, pr.capacity());
        assertEquals(List.of("w"), pr.warnings());
    }

    @Test
    void importAndViewRecordsCarryTheImportOutcome() {
        UUID id = UUID.randomUUID();
        ImportedRef ref = new ImportedRef(id, "conv-1", "Title", 4);
        assertEquals(id, ref.id());
        assertEquals("conv-1", ref.sourceKey());
        assertEquals(4, ref.messageCount());

        SkippedChat skip = new SkippedChat("conv-2", "Other", "duplicate");
        assertEquals("conv-2", skip.id());
        assertEquals("duplicate", skip.reason());

        ImportResponse ir = new ImportResponse(1, List.of(ref), List.of(skip), List.of());
        assertEquals(1, ir.imported());
        assertEquals(List.of(ref), ir.chats());
        assertEquals(List.of(skip), ir.skipped());
        assertTrue(ir.warnings().isEmpty());

        ImportedSummary sum = new ImportedSummary(id, "claude", "T", NOW, NOW, 2, false);
        assertEquals("claude", sum.source());
        assertEquals(NOW, sum.importedAt());

        ImportedMessage msg = new ImportedMessage("user", "hi", NOW, List.of("a.txt"));
        assertEquals("user", msg.role());
        assertEquals(List.of("a.txt"), msg.attachments());

        ImportedChatView view =
                new ImportedChatView(id, "claude", "T", NOW, NOW, 2, false, List.of(msg));
        assertEquals(id, view.id());
        assertEquals(1, view.messages().size());
        assertEquals(msg, view.messages().get(0));
    }
}
