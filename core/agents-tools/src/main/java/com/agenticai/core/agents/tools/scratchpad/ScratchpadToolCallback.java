package com.agenticai.core.agents.tools.scratchpad;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The four {@code scratchpad_*} tools over {@link AgentScratchpadService}: an agent's private
 * key-value notepad. Each callback is built per tool resolution with the turn's agent id and
 * session id baked in (the {@code AgentScratchpadToolResolver} pattern every first-party tool
 * uses - identity is never a model-settable argument).
 *
 * <p>Two scopes: {@code "session"} notes are this conversation's working state (a session-derived
 * UUID on the note row); {@code "agent"} notes are the agent's long-term pad (session_id NULL), so
 * they survive the conversation. When the call omits {@code scope}, session scope wins while a
 * session is known, else agent scope.
 *
 * <p>Every failure - a bad key, a note over the size cap, a pad over the entry cap - comes back as
 * a normal tool result {@code {"error": "..."}} so the model can fix the call and retry; nothing
 * here throws. And nothing here is registered with {@code ToolResultMarker}: a read or listed note
 * is agent-authored text that may carry injection harvested from earlier tool output, so it goes
 * through the normal untrusted-content screen like any other tool output, never the platform-answer
 * bypass.
 */
public final class ScratchpadToolCallback implements ToolCallback {

    public enum Kind {
        WRITE("scratchpad_write"),
        READ("scratchpad_read"),
        LIST("scratchpad_list"),
        DELETE("scratchpad_delete");

        private final String toolName;

        Kind(String toolName) {
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ScratchpadToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    /** How much of a note's body a {@code scratchpad_list} row shows. */
    static final int PREVIEW_CHARS = 80;

    private static final String SCOPE_DESCRIPTION =
            "\"session\" = this conversation's working notes, \"agent\" = the agent's long-term pad "
                    + "(kept across conversations). Omit it to use the session pad while a "
                    + "conversation is active, the agent pad otherwise.";

    private final AgentScratchpadService service;
    private final Kind kind;
    private final UUID agentId;
    /**
     * The session pad's id for this turn: {@code ctx.sessionId} is an arbitrary string (plain
     * UUIDs, {@code job:<uuid>}, scoped {@code agentic:session:c1:<hash>} ids, channel-derived
     * ids), so a non-UUID value is mapped to a stable name-based UUID - the same conversation
     * always lands on the same pad, and no session means no session scope. Null = session scope
     * unavailable this turn.
     */
    private final UUID sessionUuid;

    public ScratchpadToolCallback(
            AgentScratchpadService service, Kind kind, UUID agentId, String sessionId) {
        this.service = service;
        this.kind = kind;
        this.agentId = agentId;
        this.sessionUuid = sessionUuid(sessionId);
    }

    /** Test seam: build a callback with the session id already resolved. */
    ScratchpadToolCallback(
            AgentScratchpadService service, Kind kind, UUID agentId, UUID sessionUuid) {
        this.service = service;
        this.kind = kind;
        this.agentId = agentId;
        this.sessionUuid = sessionUuid;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return switch (kind) {
            case WRITE -> def(
                    "Writes or replaces one note on your private scratchpad (key + free-text body). Use it for "
                            + "plans, intermediate results and working state you want to come back to later in "
                            + "the task - not for facts about the user (that is remember_fact). "
                            + SCOPE_DESCRIPTION,
                    """
                    {"type":"object","properties":{
                      "key":{"type":"string","description":"Note name; short and stable, e.g. 'plan' or 'order-4211-findings'"},
                      "body":{"type":"string","description":"The full note content; writing replaces any note under the same key and scope"},
                      "scope":{"type":"string","enum":["session","agent"],"description":"Which pad the note lives on"}},
                     "required":["key","body"]}
                    """);
            case READ -> def(
                    "Reads one note back from your scratchpad by key: its body, when it was last updated and its "
                            + "size, or a no-note-found answer. " + SCOPE_DESCRIPTION,
                    """
                    {"type":"object","properties":{
                      "key":{"type":"string","description":"Note name to read"},
                      "scope":{"type":"string","enum":["session","agent"],"description":"Which pad to read"}},
                     "required":["key"]}
                    """);
            case LIST -> def(
                    "Lists the notes on your scratchpad: each row is the key, when it was last updated, its size "
                            + "in bytes and the first " + PREVIEW_CHARS + " characters of the body. Omit scope to "
                            + "see this conversation's notes and the long-term pad together; pass \"session\" or "
                            + "\"agent\" for just one.",
                    """
                    {"type":"object","properties":{
                      "scope":{"type":"string","enum":["session","agent"],"description":"Which pad to list; omit for both"}}}
                    """);
            case DELETE -> def(
                    "Deletes one note from your scratchpad by key. " + SCOPE_DESCRIPTION,
                    """
                    {"type":"object","properties":{
                      "key":{"type":"string","description":"Note name to delete"},
                      "scope":{"type":"string","enum":["session","agent"],"description":"Which pad to delete from"}},
                     "required":["key"]}
                    """);
        };
    }

    private ToolDefinition def(String description, String schema) {
        return ToolDefinition.builder()
                .name(kind.toolName)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode a = MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            ObjectNode out = MAPPER.createObjectNode();
            switch (kind) {
                case WRITE -> {
                    String key = requiredKey(a);
                    if (!a.has("body") || a.path("body").isNull()) {
                        return error("body is required - the full note content.");
                    }
                    Scope scope = scopeOf(a);
                    ScratchpadNote note =
                            service.write(agentId, scope.sessionId(), key, a.path("body").asText());
                    out.put("written", true)
                            .put("key", note.getKey())
                            .put("scope", scope.name())
                            .put("updated_at", String.valueOf(note.getUpdatedAt()))
                            .put("byte_size", note.getByteSize());
                }
                case READ -> {
                    String key = requiredKey(a);
                    Scope scope = scopeOf(a);
                    Optional<ScratchpadNote> found =
                            service.read(agentId, scope.sessionId(), key);
                    if (found.isEmpty()) {
                        out.put("found", false)
                                .put("key", key)
                                .put("scope", scope.name())
                                .put(
                                        "note",
                                        "No note found for key '" + key + "' on the " + scope.name()
                                                + " pad; scratchpad_list shows what is there.");
                    } else {
                        ScratchpadNote note = found.get();
                        out.put("found", true)
                                .put("key", note.getKey())
                                .put("scope", scope.name())
                                .put("body", note.getBody())
                                .put("updated_at", String.valueOf(note.getUpdatedAt()))
                                .put("byte_size", note.getByteSize());
                    }
                }
                case LIST -> {
                    ArrayNode notes = out.putArray("notes");
                    String scope = scopeArg(a);
                    if (scope.isEmpty()) {
                        // list(agentId, session) already merges the session pad and the
                        // agent-long-term pad, newest-updated first; a null session lists the
                        // long-term pad alone.
                        listInto(notes, service.list(agentId, sessionUuid));
                    } else {
                        Scope s = scopeOf(a);
                        List<ScratchpadNote> source = service.list(agentId, s.sessionId());
                        if ("session".equals(s.name())) {
                            // The merged read still applies: a session-scoped list keeps only this
                            // session's rows, not the agent pad findVisible adds.
                            source =
                                    source.stream()
                                            .filter(n -> n.getSessionId() != null)
                                            .toList();
                        }
                        listInto(notes, source);
                    }
                    out.put("count", notes.size());
                    if (notes.isEmpty()) {
                        out.put("note", "No notes on the scratchpad; scratchpad_write creates one.");
                    }
                }
                case DELETE -> {
                    String key = requiredKey(a);
                    Scope scope = scopeOf(a);
                    boolean deleted = service.delete(agentId, scope.sessionId(), key);
                    out.put("deleted", deleted).put("key", key).put("scope", scope.name());
                    if (!deleted) {
                        out.put(
                                "note",
                                "No note found for key '" + key + "' on the " + scope.name()
                                        + " pad; scratchpad_list shows what is there.");
                    }
                }
            }
            return MAPPER.writeValueAsString(out);
        } catch (IOException e) {
            return error("The tool arguments were not valid JSON.");
        } catch (IllegalArgumentException e) {
            return error(e.getMessage() == null ? "The call was invalid." : e.getMessage());
        } catch (RuntimeException e) {
            // Service refusals (bad key, note over the size cap, pad over the entry cap) carry a
            // message meant for the model, so it can fix the call and retry.
            log.warn("{} failed", kind.toolName, e);
            return error(
                    e.getMessage() == null || e.getMessage().isBlank()
                            ? "The scratchpad tool failed."
                            : e.getMessage());
        }
    }

    private static String requiredKey(JsonNode a) {
        String key = a.path("key").asText("").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key is required - the note's name.");
        }
        return key;
    }

    private static String scopeArg(JsonNode a) {
        return a.path("scope").asText("").trim().toLowerCase(Locale.ROOT);
    }

    /** The pad the call addresses: an explicit scope, else session-when-known else agent. */
    private Scope scopeOf(JsonNode a) {
        String scope = scopeArg(a);
        if (scope.isEmpty()) {
            return sessionUuid != null ? new Scope("session", sessionUuid) : new Scope("agent", null);
        }
        return switch (scope) {
            case "session" -> {
                if (sessionUuid == null) {
                    throw new IllegalArgumentException(
                            "No conversation session is active on this turn; use scope \"agent\".");
                }
                yield new Scope("session", sessionUuid);
            }
            case "agent" -> new Scope("agent", null);
            default -> throw new IllegalArgumentException(
                    "scope must be \"session\" or \"agent\".");
        };
    }

    private void listInto(ArrayNode notes, List<ScratchpadNote> source) {
        for (ScratchpadNote note : source) {
            ObjectNode row =
                    notes.addObject()
                            .put("key", note.getKey())
                            // A null sessionId on the row IS the agent-long-term pad.
                            .put("scope", note.getSessionId() == null ? "agent" : "session")
                            .put("updated_at", String.valueOf(note.getUpdatedAt()))
                            .put("byte_size", note.getByteSize());
            String body = note.getBody() == null ? "" : note.getBody();
            row.put(
                    "preview",
                    body.length() <= PREVIEW_CHARS ? body : body.substring(0, PREVIEW_CHARS));
        }
    }

    private record Scope(String name, UUID sessionId) {}

    static UUID sessionUuid(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sessionId.trim());
        } catch (IllegalArgumentException notAPlainUuid) {
            return UUID.nameUUIDFromBytes(sessionId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
        } catch (IOException e) {
            return "{\"error\":\"error\"}";
        }
    }
}
