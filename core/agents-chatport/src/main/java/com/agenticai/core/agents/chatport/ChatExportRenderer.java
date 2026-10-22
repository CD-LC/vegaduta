package com.agenticai.core.agents.chatport;

import com.agenticai.core.json.JsonMappers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders conversations as Markdown, plain text or versioned JSON. Pure: no Spring, no I/O.
 *
 * <p>Attachments are listed by name only (the platform keeps no binaries in a transcript, and
 * neither does an import). The JSON shape is versioned ({@value #SCHEMA} v{@value
 * #SCHEMA_VERSION}); a consumer should check {@code schema} and {@code version} before reading
 * {@code conversations[]}.
 */
public final class ChatExportRenderer {

    public static final String SCHEMA = "vegaduta.chat-export";
    public static final int SCHEMA_VERSION = 1;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /**
     * A note the chat service appends for each attachment of a user turn: {@code [attached: name
     * (mime)]}.
     */
    private static final Pattern ATTACHED_NOTE =
            Pattern.compile("(?m)^\\[attached: ([^\\n]{1,300})\\][ \\t]*$");

    private static final ObjectMapper MAPPER =
            JsonMappers.plain().enable(SerializationFeature.INDENT_OUTPUT);

    private ChatExportRenderer() {}

    public enum Format {
        MARKDOWN("md", "text/markdown; charset=utf-8"),
        JSON("json", "application/json"),
        TEXT("txt", "text/plain; charset=utf-8");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }

        /** {@code md} (default when blank), {@code json} or {@code txt}; anything else is a 400. */
        public static Format parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return MARKDOWN;
            }
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "md", "markdown" -> MARKDOWN;
                case "json" -> JSON;
                case "txt", "text" -> TEXT;
                default ->
                        throw new IllegalArgumentException(
                                "Unknown export format. Use md, json or txt.");
            };
        }
    }

    /**
     * {@code role} is user | assistant; {@code timestamp} may be null; {@code attachments} are
     * names only.
     */
    public record Message(
            String role, String content, Instant timestamp, List<String> attachments) {}

    /**
     * @param source chatgpt | claude | vegaduta
     * @param agentName the agent the conversation was held with (VegaDuta sessions), else null
     * @param notes human-readable caveats (e.g. "older turns were trimmed"); rendered, never
     *     dropped
     */
    public record Conversation(
            String id,
            String source,
            String title,
            String agentName,
            Instant createdAt,
            Instant importedAt,
            List<Message> messages,
            boolean truncated,
            List<String> notes) {}

    /** A user turn's text with its trailing {@code [attached: ...]} notes split off into names. */
    public record SplitMessage(String content, List<String> attachments) {}

    /**
     * Splits the attachment notes the chat service appends to a user turn into names; the text
     * keeps everything else.
     */
    public static SplitMessage splitAttachmentNotes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new SplitMessage("", List.of());
        }
        List<String> names = new ArrayList<>();
        Matcher m = ATTACHED_NOTE.matcher(raw);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String inner = m.group(1).strip();
            int paren = inner.lastIndexOf(" (");
            String name = paren > 0 && inner.endsWith(")") ? inner.substring(0, paren) : inner;
            if (!name.isBlank() && names.size() < ChatImportParser.MAX_ATTACHMENTS_PER_MESSAGE) {
                names.add(name);
            }
            out.append(raw, last, m.start());
            last = m.end();
        }
        out.append(raw, last, raw.length());
        return new SplitMessage(out.toString().stripTrailing(), List.copyOf(names));
    }

    public static String render(
            Format format, List<Conversation> conversations, Instant exportedAt) {
        List<Conversation> list = conversations == null ? List.of() : conversations;
        return switch (format) {
            case MARKDOWN -> markdown(list, exportedAt);
            case TEXT -> text(list, exportedAt);
            case JSON -> json(list, exportedAt);
        };
    }

    /**
     * A safe download filename: {@code <base>-<yyyyMMdd>.<ext>}, ASCII letters, digits, dot, dash
     * and underscore only.
     */
    public static String filename(String base, Instant at, Format format) {
        String b =
                base == null
                        ? ""
                        : base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (b.isEmpty()) {
            b = "chat";
        }
        if (b.length() > 60) {
            b = b.substring(0, 60);
        }
        return b + "-" + DAY.format(at == null ? Instant.now() : at) + "." + format.extension();
    }

    // ---- markdown ----

    private static String markdown(List<Conversation> conversations, Instant exportedAt) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Conversation c : conversations) {
            if (!first) {
                sb.append("\n---\n\n");
            }
            first = false;
            sb.append("# ").append(headingLine(c.title())).append("\n\n");
            sb.append('*').append(metaLine(c, exportedAt)).append("*\n\n");
            for (String note : safe(c.notes())) {
                sb.append("> ").append(headingLine(note)).append("\n\n");
            }
            for (Message m : safe(c.messages())) {
                sb.append("### ").append(roleLabel(m.role()));
                if (m.timestamp() != null) {
                    sb.append(" · ").append(STAMP.format(m.timestamp()));
                }
                sb.append("\n\n");
                String body = m.content() == null ? "" : m.content().strip();
                if (!body.isEmpty()) {
                    sb.append(body).append("\n\n");
                }
                if (m.attachments() != null && !m.attachments().isEmpty()) {
                    sb.append("> Attachments: ")
                            .append(String.join(", ", m.attachments()))
                            .append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    // ---- plain text ----

    private static String text(List<Conversation> conversations, Instant exportedAt) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Conversation c : conversations) {
            if (!first) {
                sb.append("\n==========\n\n");
            }
            first = false;
            String title = headingLine(c.title());
            sb.append(title)
                    .append('\n')
                    .append("=".repeat(Math.max(3, Math.min(title.length(), 80))))
                    .append('\n');
            sb.append(metaLine(c, exportedAt)).append("\n\n");
            for (String note : safe(c.notes())) {
                sb.append("Note: ").append(headingLine(note)).append("\n\n");
            }
            for (Message m : safe(c.messages())) {
                sb.append(roleLabel(m.role()));
                if (m.timestamp() != null) {
                    sb.append(" [").append(STAMP.format(m.timestamp())).append(']');
                }
                sb.append(":\n");
                String body = m.content() == null ? "" : m.content().strip();
                if (!body.isEmpty()) {
                    sb.append(body).append('\n');
                }
                if (m.attachments() != null && !m.attachments().isEmpty()) {
                    sb.append("(Attachments: ")
                            .append(String.join(", ", m.attachments()))
                            .append(")\n");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // ---- json ----

    private static String json(List<Conversation> conversations, Instant exportedAt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", SCHEMA);
        root.put("version", SCHEMA_VERSION);
        root.put("exportedAt", (exportedAt == null ? Instant.now() : exportedAt).toString());
        ArrayNode convs = root.putArray("conversations");
        for (Conversation c : conversations) {
            ObjectNode o = convs.addObject();
            o.put("id", c.id());
            o.put("source", c.source());
            o.put("title", c.title());
            if (c.agentName() != null) {
                o.put("agent", c.agentName());
            }
            o.put("createdAt", c.createdAt() == null ? null : c.createdAt().toString());
            if (c.importedAt() != null) {
                o.put("importedAt", c.importedAt().toString());
            }
            o.put("truncated", c.truncated());
            ArrayNode notes = o.putArray("notes");
            for (String n : safe(c.notes())) {
                notes.add(n);
            }
            ArrayNode msgs = o.putArray("messages");
            for (Message m : safe(c.messages())) {
                ObjectNode mo = msgs.addObject();
                mo.put("role", m.role());
                mo.put("content", m.content() == null ? "" : m.content());
                mo.put("timestamp", m.timestamp() == null ? null : m.timestamp().toString());
                ArrayNode atts = mo.putArray("attachments");
                for (String a : safe(m.attachments())) {
                    atts.add(a);
                }
            }
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not render the export.", e);
        }
    }

    // ---- shared ----

    private static String metaLine(Conversation c, Instant exportedAt) {
        List<String> bits = new ArrayList<>();
        bits.add("Source: " + sourceLabel(c.source()));
        if (c.agentName() != null && !c.agentName().isBlank()) {
            bits.add("Agent: " + headingLine(c.agentName()));
        }
        if (c.createdAt() != null) {
            bits.add("Started " + STAMP.format(c.createdAt()));
        }
        if (c.importedAt() != null) {
            bits.add("Imported " + STAMP.format(c.importedAt()));
        }
        bits.add("Exported " + STAMP.format(exportedAt == null ? Instant.now() : exportedAt));
        if (c.truncated()) {
            bits.add("Long messages or very long chats were shortened on import");
        }
        return String.join(" · ", bits);
    }

    private static String sourceLabel(String source) {
        if (source == null) {
            return "unknown";
        }
        return switch (source) {
            case ChatImportParser.SOURCE_CHATGPT -> "ChatGPT";
            case ChatImportParser.SOURCE_CLAUDE -> "Claude";
            case "vegaduta" -> "VegaDuta";
            default -> source;
        };
    }

    private static String roleLabel(String role) {
        return ChatImportParser.ROLE_USER.equals(role) ? "You" : "Assistant";
    }

    /** One line, no markdown heading characters at the start. */
    private static String headingLine(String s) {
        String one = ChatPortText.oneLine(s == null ? "" : s);
        return one.isEmpty() ? "Untitled chat" : one;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
