package com.agenticai.core.agents.chatport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Reads the conversation exports of ChatGPT ({@code conversations.json}: {@code {title,
 * create_time, mapping{id -> {message, parent, children}}, current_node}}) and Claude ({@code
 * conversations.json}: {@code {uuid, name, created_at, chat_messages[{sender, text, content[]}]}}),
 * either as the bare JSON or inside the export .zip. Pure and deterministic: no Spring, no model,
 * no I/O beyond the byte array it is handed.
 *
 * <p>The file is hostile input. The JSON is read as a stream, one conversation at a time (never the
 * whole document as one tree), with Jackson read constraints on nesting depth and string size and a
 * node/character budget for each conversation while it is read (over budget it is skipped
 * unmaterialised); the ChatGPT message tree is walked from {@code current_node} up the {@code
 * parent} chain with a visited set and a step cap, so a cyclic or enormous {@code mapping} cannot
 * loop or exhaust memory; message, character and conversation counts are capped ({@link Limits});
 * and the zip reader counts every inflated byte (entries that are not conversation files included)
 * against an entry cap, an uncompressed-size cap and a compression-ratio cap. Only {@code user} and
 * {@code assistant} text survives: system/tool/hidden messages and unknown content types are
 * skipped, attachment binaries are never read (names only), and control / bidi / TAG-block
 * characters are stripped.
 *
 * <p>Formats verified against the shapes ChatGPT and Claude "export your data" produce; ChatGPT's
 * {@code mapping}/{@code current_node} structure is described by the community export-format
 * write-ups, and the parser deliberately tolerates missing fields rather than relying on any single
 * version of either export.
 *
 * <p>This class is the entry point and owns the public result types. The work is split by concern:
 * {@link ChatImportReader} (zip container, streamed JSON, per-conversation tree budget), {@link
 * ChatImportCollector} (format dispatch, dedupe, selection, caps, warnings), one {@link
 * ChatImportFormat} per source format ({@link ChatGptImportFormat}, {@link ClaudeImportFormat},
 * {@link VegaDutaImportFormat}), {@link ChatImportMessageBuilder} (per-chat caps) and {@link
 * ChatImportFields} (field cleaning).
 */
public final class ChatImportParser {

    public static final String SOURCE_CHATGPT = "chatgpt";
    public static final String SOURCE_CLAUDE = "claude";

    /** A conversation held on this platform and exported by {@link ChatExportRenderer}. */
    public static final String SOURCE_VEGADUTA = "vegaduta";

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    static final int MAX_NESTING_DEPTH = 64;
    static final int MAX_STRING_CHARS = 10_000_000;
    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_ID_CHARS = 128;
    static final int MAX_ATTACHMENT_NAME_CHARS = 200;
    static final int MAX_ATTACHMENTS_PER_MESSAGE = 20;
    static final int MAX_WARNINGS = 20;
    static final int FIRST_USER_TEXT_CHARS = 400;
    static final String TRUNCATED_MARK = "\n[truncated]";

    private ChatImportParser() {}

    /**
     * All caps in one place. {@code maxConversations}: conversations read from the file (later ones
     * are ignored and {@link ParseResult#capReached} is set). {@code maxCharsPerImport}: total
     * message characters retained across the conversations kept (only enforced when messages are
     * kept). {@code maxMappingNodes}: a ChatGPT conversation with a bigger {@code mapping} is
     * skipped. {@code maxTreeNodes} / {@code maxTreeChars}: the budget for ONE conversation while
     * it is read into memory (JSON values, and characters of strings and field names): a
     * conversation that exceeds either is skipped without being materialised, so a single enormous
     * array or object cannot exhaust the heap whatever the file or zip size.
     */
    public record Limits(
            int maxConversations,
            int maxMessagesPerChat,
            int maxCharsPerMessage,
            int maxCharsPerChat,
            long maxCharsPerImport,
            int maxMappingNodes,
            int maxZipEntries,
            long maxUncompressedBytes,
            int maxZipRatio,
            int maxTreeNodes,
            long maxTreeChars) {
        public static final int DEFAULT_TREE_NODES = 1_000_000;
        public static final long DEFAULT_TREE_CHARS = 16_000_000L;
        public static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;

        /** The nine original caps with the default per-conversation tree budget. */
        public Limits(
                int maxConversations,
                int maxMessagesPerChat,
                int maxCharsPerMessage,
                int maxCharsPerChat,
                long maxCharsPerImport,
                int maxMappingNodes,
                int maxZipEntries,
                long maxUncompressedBytes,
                int maxZipRatio) {
            this(
                    maxConversations,
                    maxMessagesPerChat,
                    maxCharsPerMessage,
                    maxCharsPerChat,
                    maxCharsPerImport,
                    maxMappingNodes,
                    maxZipEntries,
                    maxUncompressedBytes,
                    maxZipRatio,
                    DEFAULT_TREE_NODES,
                    DEFAULT_TREE_CHARS);
        }

        public static Limits defaults() {
            return new Limits(
                    5000,
                    2000,
                    50_000,
                    500_000,
                    20_000_000L,
                    20_000,
                    10_000,
                    DEFAULT_MAX_UNCOMPRESSED_BYTES,
                    100);
        }
    }

    /** One kept message. {@code timestamp} and {@code attachments} (names only) may be empty. */
    public record ParsedMessage(
            String role, String content, Instant timestamp, List<String> attachments) {}

    /**
     * One conversation. {@code key} is the stable id the preview exposes and the import selects by
     * (the source's own id, else {@code idx-<n>}). {@code messages} is empty when parsed without
     * keeping messages (preview); {@code messageCount}, {@code chars} and {@code firstUserText}
     * (raw, at most {@value #FIRST_USER_TEXT_CHARS} chars) are always set.
     */
    public record ParsedChat(
            String key,
            String source,
            String sourceId,
            String title,
            Instant createdAt,
            Instant updatedAt,
            List<ParsedMessage> messages,
            int messageCount,
            long chars,
            String firstUserText,
            boolean truncated) {}

    /**
     * @param totalConversations conversations seen in the file (up to the scan cap)
     * @param skipped conversations not usable: unknown shape, no importable messages, oversized,
     *     duplicate id, or over the size budget
     * @param capReached the file holds more conversations than {@link Limits#maxConversations()}
     */
    public record ParseResult(
            List<ParsedChat> chats,
            int totalConversations,
            int skipped,
            boolean capReached,
            List<String> warnings) {}

    /**
     * @param data the uploaded file (JSON or zip), already size-checked by the caller
     * @param keepMessages false for a preview (counts and a snippet only)
     * @param selected conversation keys to convert, or null for all; others are read past without
     *     converting
     * @throws IllegalArgumentException with a user-presentable message when the file is not a
     *     usable export
     */
    public static ParseResult parse(
            byte[] data, Limits limits, boolean keepMessages, Set<String> selected) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("The file is empty.");
        }
        ChatImportCollector c = new ChatImportCollector(limits, keepMessages, selected);
        try {
            ChatImportReader.read(data, c);
        } catch (ChatImportReader.LimitExceededException e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "That file is not valid chat-export JSON, or it nests or stores values beyond the safe limits.");
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "That file could not be read as a chat export (is the .zip damaged?).");
        }
        return c.result();
    }

    /**
     * ChatGPT timestamps are epoch seconds (a double); anything non-finite, non-positive or past
     * 2100 is dropped.
     */
    static Instant epochSeconds(JsonNode v) {
        return ChatImportFields.epochSeconds(v);
    }

    static Instant parseIso(String s) {
        return ChatImportFields.parseIso(s);
    }
}
