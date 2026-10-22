package com.agenticai.core.agents.chatport;

import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_NESTING_DEPTH;
import static com.agenticai.core.agents.chatport.ChatImportParser.MAX_STRING_CHARS;

import com.agenticai.core.agents.chatport.ChatImportParser.Limits;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The container and stream layer of {@link ChatImportParser}: unwraps the export .zip under an
 * entry, inflated-size and compression-ratio budget, streams the JSON one conversation at a time
 * under Jackson read constraints, and reads each conversation into a tree under a node/character
 * budget before handing it to the {@link ChatImportCollector}. Knows nothing about any source
 * format.
 */
final class ChatImportReader {

    /** The array field of {@link ChatExportRenderer}'s JSON envelope. */
    private static final String ENVELOPE_CONVERSATIONS = "conversations";

    private static final Pattern CONVERSATIONS_ENTRY =
            Pattern.compile("conversations(?:-\\d{1,6})?\\.json");

    private static final ObjectMapper MAPPER =
            JsonMappers.withFactory(
                    JsonFactory.builder()
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(MAX_NESTING_DEPTH)
                                            .maxStringLength(MAX_STRING_CHARS)
                                            .maxNumberLength(64)
                                            .build())
                            .build());

    private ChatImportReader() {}

    /** Reads {@code data} (a zip or bare JSON) into {@code c}. */
    static void read(byte[] data, ChatImportCollector c) throws IOException {
        if (isZip(data)) {
            readZip(data, c);
        } else {
            readConversations(new ByteArrayInputStream(data), c);
        }
    }

    /** A cap was hit or the file has the wrong shape; the message is user-presentable. */
    static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        LimitExceededException(String message) {
            super(message);
        }
    }

    // ---- container handling ----

    private static boolean isZip(byte[] d) {
        return d.length > 3 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    private static void readZip(byte[] data, ChatImportCollector c) throws IOException {
        Limits limits = c.limits;
        long ratioBudget =
                Math.max(1_048_576L, (long) data.length * Math.max(1, limits.maxZipRatio()));
        long budget = Math.min(limits.maxUncompressedBytes(), ratioBudget);
        Counter counter = new Counter(budget);
        try (ZipInputStream zin =
                new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            int entries = 0;
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxZipEntries()) {
                    throw new LimitExceededException(
                            "That .zip holds too many files to import safely.");
                }
                InputStream in = new CountingInputStream(zin, counter);
                if (!entry.isDirectory() && isConversationsEntry(entry.getName()) && !c.done()) {
                    readConversations(in, c);
                }
                drain(in);
            }
        }
    }

    private static boolean isConversationsEntry(String name) {
        if (name == null) {
            return false;
        }
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        String base = (slash >= 0 ? n.substring(slash + 1) : n).toLowerCase(Locale.ROOT);
        return CONVERSATIONS_ENTRY.matcher(base).matches();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) >= 0) {
            // discard: every inflated byte is counted against the zip-bomb budget by
            // CountingInputStream
        }
    }

    /** Shared inflated-byte budget across every entry of one zip. */
    private static final class Counter {
        private final long budget;
        private long used;

        Counter(long budget) {
            this.budget = budget;
        }

        void add(int n) throws LimitExceededException {
            used += n;
            if (used > budget) {
                throw new LimitExceededException(
                        "That .zip expands to far more data than a chat export should; it was not imported.");
            }
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final Counter counter;

        CountingInputStream(InputStream in, Counter counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                counter.add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                counter.add(n);
            }
            return n;
        }

        @Override
        public void close() {
            // the zip stream outlives one entry; the caller closes it
        }
    }

    // ---- streamed JSON ----

    /** Streams the top-level array (or a single conversation object) one element at a time. */
    private static void readConversations(InputStream in, ChatImportCollector c)
            throws IOException {
        try (JsonParser p = MAPPER.createParser(in)) {
            JsonToken first = p.nextToken();
            if (first == JsonToken.START_ARRAY) {
                JsonToken t;
                while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                    if (c.done()) {
                        return;
                    }
                    if (t == JsonToken.START_OBJECT) {
                        acceptBounded(p, c);
                    } else {
                        p.skipChildren();
                        c.skipped++;
                    }
                }
            } else if (first == JsonToken.START_OBJECT) {
                readTopLevelObject(p, c);
            } else {
                throw new LimitExceededException(
                        "That file does not look like a ChatGPT, Claude or VegaDuta conversations export "
                                + "(expected a JSON list of conversations).");
            }
        }
    }

    /**
     * A top-level JSON object is either ONE conversation (a single ChatGPT / Claude conversation
     * saved on its own) or an export envelope: this platform's own JSON export is {@code {"schema":
     * "vegaduta.chat-export", "version": 1, "exportedAt": ..., "conversations": [...]}} ({@link
     * ChatExportRenderer}). The envelope used to be read as one conversation, matched neither known
     * shape and was skipped - so the app's own export answered "No importable conversations were
     * found" on re-import (prod, 2026-10-05).
     *
     * <p>Streamed like the top-level array: each element of {@code conversations} is read under its
     * own per-conversation budget, so an export of many chats is never held as one tree. Every
     * other field of the object is read under one shared budget; when no {@code conversations}
     * array turns up, those fields ARE the single conversation and go to the collector exactly as
     * before.
     */
    private static void readTopLevelObject(JsonParser p, ChatImportCollector c) throws IOException {
        TreeBudget budget = new TreeBudget(c.limits.maxTreeNodes(), c.limits.maxTreeChars());
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        boolean envelope = false;
        budget.depth = 1;
        try {
            budget.node();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if (ENVELOPE_CONVERSATIONS.equals(name) && value == JsonToken.START_ARRAY) {
                    envelope = true;
                    JsonToken t;
                    while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                        if (c.done()) {
                            return;
                        }
                        if (t == JsonToken.START_OBJECT) {
                            acceptBounded(p, c);
                        } else {
                            p.skipChildren();
                            c.skipped++;
                        }
                    }
                    continue;
                }
                budget.chars(name.length());
                fields.set(name, readBounded(p, budget));
            }
        } catch (TreeBudgetExceededException e) {
            skipRest(p, budget.depth);
            if (!envelope) {
                c.skipped++;
                c.warn("A conversation that is far larger than a chat should be was skipped.");
            }
            return;
        }
        if (!envelope) {
            c.accept(fields);
        }
    }

    /**
     * Reads the conversation object the parser is positioned on into a tree under a node and
     * character budget ({@link Limits#maxTreeNodes()} / {@link Limits#maxTreeChars()}), then hands
     * it to the collector. Over budget the rest of the object is read past token by token (nothing
     * is retained) and the conversation is counted as skipped. This replaces {@code
     * ObjectMapper.readTree}, which would build any object, however wide, in full before a single
     * cap ran (nesting depth and string length are limited by the Jackson read constraints, width
     * was not).
     */
    private static void acceptBounded(JsonParser p, ChatImportCollector c) throws IOException {
        TreeBudget budget = new TreeBudget(c.limits.maxTreeNodes(), c.limits.maxTreeChars());
        JsonNode node;
        try {
            node = readBounded(p, budget);
        } catch (TreeBudgetExceededException e) {
            skipRest(p, budget.depth);
            c.skipped++;
            c.warn("A conversation that is far larger than a chat should be was skipped.");
            return;
        }
        c.accept(node);
    }

    /**
     * Consumes tokens until the {@code open} containers that are still open have all been closed.
     */
    private static void skipRest(JsonParser p, int open) throws IOException {
        int depth = open;
        while (depth > 0) {
            JsonToken t = p.nextToken();
            if (t == null) {
                return;
            }
            if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                depth++;
            } else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
                depth--;
            }
        }
    }

    // ---- per-conversation tree budget ----

    private static final class TreeBudget {
        private final int maxNodes;
        private final long maxChars;
        private int nodes;
        private long chars;

        /**
         * Containers entered and not yet left; on a budget failure, how many the caller must still
         * close.
         */
        int depth;

        TreeBudget(int maxNodes, long maxChars) {
            this.maxNodes = maxNodes;
            this.maxChars = maxChars;
        }

        void node() {
            if (++nodes > maxNodes) {
                throw new TreeBudgetExceededException();
            }
        }

        void chars(int n) {
            chars += n;
            if (chars > maxChars) {
                throw new TreeBudgetExceededException();
            }
        }
    }

    private static final class TreeBudgetExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TreeBudgetExceededException() {
            super("conversation tree over budget", null, false, false);
        }
    }

    /** The value starting at the parser's current token, built under {@code budget}. */
    private static JsonNode readBounded(JsonParser p, TreeBudget budget) throws IOException {
        JsonToken t = p.currentToken();
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (t == JsonToken.START_OBJECT) {
            budget.depth++;
            budget.node();
            ObjectNode obj = f.objectNode();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                budget.chars(name.length());
                p.nextToken();
                obj.set(name, readBounded(p, budget));
            }
            budget.depth--;
            return obj;
        }
        if (t == JsonToken.START_ARRAY) {
            budget.depth++;
            budget.node();
            ArrayNode arr = f.arrayNode();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == null) {
                    throw new LimitExceededException(
                            "That file ended in the middle of a conversation.");
                }
                arr.add(readBounded(p, budget));
            }
            budget.depth--;
            return arr;
        }
        budget.node();
        if (t == JsonToken.VALUE_STRING) {
            String s = p.getText();
            budget.chars(s.length());
            return f.textNode(s);
        }
        if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
            return f.numberNode(p.getDoubleValue());
        }
        if (t == JsonToken.VALUE_TRUE) {
            return f.booleanNode(true);
        }
        if (t == JsonToken.VALUE_FALSE) {
            return f.booleanNode(false);
        }
        return f.nullNode();
    }
}
