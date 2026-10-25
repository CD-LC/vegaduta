package com.agenticai.core.agents.tools.scratchpad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four {@code scratchpad_*} callbacks against an in-memory fake of {@link
 * AgentScratchpadService}: write/read round trip, session-vs-agent scope mapping, the {@code
 * {"error": ...}} shape for service refusals, the list row format and delete.
 */
class ScratchpadToolCallbackTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-" + UUID.randomUUID();

    private AgentScratchpadService service;

    @BeforeEach
    void setUp() {
        service = fake();
    }

    /** A stand-in for the real note entity: the tools read key/body/sessionId/byteSize/updatedAt. */
    private static ScratchpadNote note(UUID sessionId, String key, String body) {
        ScratchpadNote n = mock(ScratchpadNote.class);
        lenient().when(n.getKey()).thenReturn(key);
        lenient().when(n.getBody()).thenReturn(body);
        lenient().when(n.getSessionId()).thenReturn(sessionId);
        lenient()
                .when(n.getByteSize())
                .thenReturn(body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length);
        lenient().when(n.getUpdatedAt()).thenReturn(Instant.parse("2026-01-02T03:04:05Z"));
        return n;
    }

    private static String storeKey(UUID sessionId, String key) {
        return (sessionId == null ? "agent" : sessionId.toString()) + "|" + key;
    }

    /**
     * In-memory {@link AgentScratchpadService}: notes keyed by (pad, key), upsert semantics, and
     * the real {@code list} behaviour - a non-null session sees its own notes AND the agent pad,
     * a null session sees the agent pad alone.
     */
    private static AgentScratchpadService fake() {
        AgentScratchpadService svc = mock(AgentScratchpadService.class);
        Map<String, ScratchpadNote> store = new LinkedHashMap<>();
        when(svc.write(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            ScratchpadNote n =
                                    note(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                            store.put(storeKey(inv.getArgument(1), inv.getArgument(2)), n);
                            return n;
                        });
        when(svc.read(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        store.get(
                                                storeKey(
                                                        inv.getArgument(1), inv.getArgument(2)))));
        when(svc.list(any(), any()))
                .thenAnswer(
                        inv -> {
                            UUID session = inv.getArgument(1);
                            List<ScratchpadNote> out = new ArrayList<>();
                            for (Map.Entry<String, ScratchpadNote> e : store.entrySet()) {
                                String pad = e.getKey().substring(0, e.getKey().indexOf('|'));
                                if ("agent".equals(pad)
                                        || (session != null && pad.equals(session.toString()))) {
                                    out.add(e.getValue());
                                }
                            }
                            return out;
                        });
        when(svc.delete(any(), any(), any()))
                .thenAnswer(
                        inv ->
                                store.remove(storeKey(inv.getArgument(1), inv.getArgument(2)))
                                        != null);
        return svc;
    }

    private JsonNode call(ScratchpadToolCallback.Kind kind, String json) throws Exception {
        return call(kind, json, SESSION);
    }

    private JsonNode call(ScratchpadToolCallback.Kind kind, String json, String sessionId)
            throws Exception {
        return JsonMappers.plain()
                .readTree(
                        new ScratchpadToolCallback(service, kind, AGENT, sessionId).call(json));
    }

    @Test
    void toolNamesAndRequiredArgsAreTheDocumentedOnes() {
        for (ScratchpadToolCallback.Kind kind : ScratchpadToolCallback.Kind.values()) {
            var def = new ScratchpadToolCallback(service, kind, AGENT, SESSION).getToolDefinition();
            assertThat(def.name()).startsWith("scratchpad_");
            assertThat(def.inputSchema()).contains("\"scope\"");
        }
        assertThat(
                        new ScratchpadToolCallback(
                                        service, ScratchpadToolCallback.Kind.WRITE, AGENT, SESSION)
                                .getToolDefinition()
                                .inputSchema())
                .contains("\"required\":[\"key\",\"body\"]");
    }

    @Test
    void writeThenReadRoundTripsTheBody() throws Exception {
        JsonNode w = call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"plan\",\"body\":\"step 1\"}");
        assertThat(w.path("written").asBoolean()).isTrue();
        assertThat(w.path("scope").asText()).isEqualTo("session");

        JsonNode r = call(ScratchpadToolCallback.Kind.READ, "{\"key\":\"plan\"}");
        assertThat(r.path("found").asBoolean()).isTrue();
        assertThat(r.path("body").asText()).isEqualTo("step 1");
        assertThat(r.has("updated_at")).isTrue();
        assertThat(r.has("byte_size")).isTrue();
    }

    @Test
    void sessionScopeIsTheDefaultOnlyWhileASessionIsKnown() throws Exception {
        // No session on the turn: a scope-less write lands on the agent pad (session_id null).
        JsonNode w =
                call(
                        ScratchpadToolCallback.Kind.WRITE,
                        "{\"key\":\"k\",\"body\":\"v\"}",
                        null);
        assertThat(w.path("scope").asText()).isEqualTo("agent");
        JsonNode r =
                call(ScratchpadToolCallback.Kind.READ, "{\"key\":\"k\",\"scope\":\"agent\"}", null);
        assertThat(r.path("found").asBoolean()).isTrue();
    }

    @Test
    void sessionScopeKeepsSessionsApartAndAgentScopeIsSharedAcrossSessions() throws Exception {
        String otherSession = "other-" + UUID.randomUUID();
        call(
                ScratchpadToolCallback.Kind.WRITE,
                "{\"key\":\"k\",\"body\":\"mine\"}",
                SESSION);
        call(
                ScratchpadToolCallback.Kind.WRITE,
                "{\"key\":\"k\",\"body\":\"theirs\"}",
                otherSession);
        call(
                ScratchpadToolCallback.Kind.WRITE,
                "{\"key\":\"shared\",\"body\":\"long-lived\",\"scope\":\"agent\"}",
                SESSION);

        JsonNode mine =
                call(ScratchpadToolCallback.Kind.READ, "{\"key\":\"k\",\"scope\":\"session\"}", SESSION);
        assertThat(mine.path("body").asText()).isEqualTo("mine");
        JsonNode theirs =
                call(
                        ScratchpadToolCallback.Kind.READ,
                        "{\"key\":\"k\",\"scope\":\"session\"}",
                        otherSession);
        assertThat(theirs.path("body").asText()).isEqualTo("theirs");
        JsonNode shared =
                call(
                        ScratchpadToolCallback.Kind.READ,
                        "{\"key\":\"shared\",\"scope\":\"agent\"}",
                        otherSession);
        assertThat(shared.path("body").asText()).isEqualTo("long-lived");
    }

    @Test
    void anExplicitSessionScopeWithoutASessionIsAnErrorNotASilentAgentWrite() throws Exception {
        JsonNode r =
                call(
                        ScratchpadToolCallback.Kind.WRITE,
                        "{\"key\":\"k\",\"body\":\"v\",\"scope\":\"session\"}",
                        null);
        assertThat(r.path("error").asText()).contains("scope \"agent\"");
        JsonNode l = call(ScratchpadToolCallback.Kind.LIST, "{\"scope\":\"session\"}", null);
        assertThat(l.has("error")).isTrue();
    }

    @Test
    void unknownScopeAndMissingArgsComeBackAsErrorJson() throws Exception {
        assertThat(
                        call(
                                        ScratchpadToolCallback.Kind.WRITE,
                                        "{\"key\":\"k\",\"body\":\"v\",\"scope\":\"tenant\"}")
                                .path("error")
                                .asText())
                .contains("scope");
        assertThat(call(ScratchpadToolCallback.Kind.WRITE, "{\"body\":\"v\"}").path("error").asText())
                .contains("key");
        assertThat(call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"k\"}").path("error").asText())
                .contains("body");
        assertThat(call(ScratchpadToolCallback.Kind.READ, "not json").has("error")).isTrue();
    }

    @Test
    void serviceRefusalsAreErrorJsonTheModelCanFix() throws Exception {
        when(service.write(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("note is over the 8192-byte cap"));
        JsonNode w =
                call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"k\",\"body\":\"too big\"}");
        assertThat(w.path("error").asText()).contains("8192-byte cap");
    }

    @Test
    void aMissingNoteReadsAndDeletesAsNotFound() throws Exception {
        JsonNode r = call(ScratchpadToolCallback.Kind.READ, "{\"key\":\"nope\"}");
        assertThat(r.path("found").asBoolean()).isFalse();
        assertThat(r.path("note").asText()).contains("No note found");
        JsonNode d = call(ScratchpadToolCallback.Kind.DELETE, "{\"key\":\"nope\"}");
        assertThat(d.path("deleted").asBoolean()).isFalse();
    }

    @Test
    void deleteRemovesTheNoteFromItsPadOnly() throws Exception {
        call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"k\",\"body\":\"s\",\"scope\":\"session\"}");
        call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"k\",\"body\":\"a\",\"scope\":\"agent\"}");
        JsonNode d = call(ScratchpadToolCallback.Kind.DELETE, "{\"key\":\"k\",\"scope\":\"session\"}");
        assertThat(d.path("deleted").asBoolean()).isTrue();
        JsonNode agent = call(ScratchpadToolCallback.Kind.READ, "{\"key\":\"k\",\"scope\":\"agent\"}");
        assertThat(agent.path("body").asText()).isEqualTo("a");
    }

    @Test
    void listMergesBothPadsAndShowsKeyTimestampSizeAndPreview() throws Exception {
        String longBody = "x".repeat(ScratchpadToolCallback.PREVIEW_CHARS + 20);
        call(ScratchpadToolCallback.Kind.WRITE, "{\"key\":\"short\",\"body\":\"session note\"}");
        call(
                ScratchpadToolCallback.Kind.WRITE,
                "{\"key\":\"long\",\"body\":\"" + longBody + "\",\"scope\":\"agent\"}");

        JsonNode both = call(ScratchpadToolCallback.Kind.LIST, "{}");
        assertThat(both.path("count").asInt()).isEqualTo(2);
        JsonNode first = both.path("notes").get(0);
        assertThat(first.path("key").asText()).isEqualTo("short");
        assertThat(first.path("scope").asText()).isEqualTo("session");
        assertThat(first.path("updated_at").asText()).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(first.path("byte_size").asInt()).isEqualTo("session note".getBytes().length);
        assertThat(first.path("preview").asText()).isEqualTo("session note");
        JsonNode second = both.path("notes").get(1);
        assertThat(second.path("scope").asText()).isEqualTo("agent");
        assertThat(second.path("preview").asText())
                .hasSize(ScratchpadToolCallback.PREVIEW_CHARS);

        JsonNode sessionOnly = call(ScratchpadToolCallback.Kind.LIST, "{\"scope\":\"session\"}");
        assertThat(sessionOnly.path("count").asInt()).isEqualTo(1);
        assertThat(sessionOnly.path("notes").get(0).path("scope").asText()).isEqualTo("session");

        // A fresh session has no session notes, but the merged list still shows the agent pad.
        JsonNode freshSession =
                call(
                        ScratchpadToolCallback.Kind.LIST,
                        "{}",
                        "empty-" + UUID.randomUUID());
        assertThat(freshSession.path("count").asInt()).isEqualTo(1);
        assertThat(freshSession.path("notes").get(0).path("scope").asText()).isEqualTo("agent");

        // With no session at all, the merged list IS just the agent pad.
        JsonNode agentRows =
                JsonMappers.plain()
                        .readTree(
                                new ScratchpadToolCallback(
                                                service,
                                                ScratchpadToolCallback.Kind.LIST,
                                                AGENT,
                                                (UUID) null)
                                        .call("{}"));
        assertThat(agentRows.path("count").asInt()).isEqualTo(1);
        assertThat(agentRows.path("notes").get(0).path("scope").asText()).isEqualTo("agent");
    }
}
