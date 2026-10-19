package com.agenticai.core.agents.chatport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of {@code /api/chat-import} - see {@link ChatImportService}. */
public final class ChatPortDtos {

    private ChatPortDtos() {
    }

    /**
     * What {@code GET /api/chat-import/features} answers (always 200 for a signed-in caller, so the web can decide
     * which controls to render): which halves are switched on and the limits the import card shows.
     */
    public record Features(boolean importEnabled, boolean exportEnabled, long maxBytes, int maxImportConversations) {
    }

    /** JSON-body alternative to the multipart upload: the raw {@code conversations.json} text, plus the chosen keys on import. */
    public record JsonUpload(String text, List<String> ids) {
    }

    /**
     * One conversation found in the file. {@code id} is the stable key to send back on import. {@code snippet} is a
     * PII-screened, truncated start of the first user message ("[hidden]" when it looks sensitive).
     */
    public record PreviewConversation(
        String id,
        String source,
        String title,
        Instant createdAt,
        int messageCount,
        String snippet,
        boolean alreadyImported,
        boolean truncated
    ) {
    }

    /**
     * @param found       conversations read from the file
     * @param listed      conversations with importable messages (the length of {@code conversations})
     * @param skipped     conversations that could not be used (unknown shape, no importable text, oversized, duplicate)
     * @param scanCapReached the file holds more conversations than are read in one go
     */
    public record PreviewStats(int found, int listed, int skipped, boolean scanCapReached) {
    }

    /** Room left: {@code storedChats} of {@code maxStoredChats} already imported; {@code maxPerImport} per request. */
    public record Capacity(long storedChats, int maxStoredChats, int maxPerImport) {
    }

    public record PreviewResponse(String source, List<PreviewConversation> conversations, PreviewStats stats, Capacity capacity,
                                  List<String> warnings) {
    }

    /** {@code reason}: duplicate | limit_reached | not_found | empty (stable codes the UI maps to words). */
    public record SkippedChat(String id, String title, String reason) {
    }

    public record ImportedRef(UUID id, String sourceKey, String title, int messageCount) {
    }

    public record ImportResponse(int imported, List<ImportedRef> chats, List<SkippedChat> skipped, List<String> warnings) {
    }

    public record ImportedSummary(UUID id, String source, String title, Instant createdAt, Instant importedAt, int messageCount,
                                  boolean truncated) {
    }

    public record ImportedMessage(String role, String content, Instant timestamp, List<String> attachments) {
    }

    public record ImportedChatView(UUID id, String source, String title, Instant createdAt, Instant importedAt, int messageCount,
                                   boolean truncated, List<ImportedMessage> messages) {
    }

    public record MemoryCandidatesRequest(List<UUID> chatIds) {
    }
}
