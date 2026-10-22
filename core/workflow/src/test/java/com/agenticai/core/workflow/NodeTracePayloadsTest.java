package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NodeTracePayloadsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    private static NodeTraceEntry entry() {
        return new NodeTraceEntry(
                "n1", "agent", "Agent", "OK", "ok", T0, T0.plusMillis(1500), List.of());
    }

    // --- truncation -------------------------------------------------------

    @Test
    void shortPayloadIsStoredWhole() {
        NodeTracePayloads.Sanitized s =
                NodeTracePayloads.sanitize("hello world", NodeTracePayloads.MAX_FIELD_CHARS);
        assertEquals("hello world", s.text());
        assertFalse(s.truncated());
    }

    @Test
    void payloadOverCapIsCutAndFlagged() {
        String big = "a".repeat(NodeTracePayloads.MAX_FIELD_CHARS + 500);
        NodeTracePayloads.Sanitized s =
                NodeTracePayloads.sanitize(big, NodeTracePayloads.MAX_FIELD_CHARS);
        assertEquals(NodeTracePayloads.MAX_FIELD_CHARS, s.text().length());
        assertTrue(s.truncated());
    }

    @Test
    void payloadExactlyAtCapIsNotFlagged() {
        String exact = "b".repeat(NodeTracePayloads.MAX_FIELD_CHARS);
        assertFalse(
                NodeTracePayloads.sanitize(exact, NodeTracePayloads.MAX_FIELD_CHARS).truncated());
    }

    @Test
    void nullStaysNull() {
        NodeTracePayloads.Sanitized s = NodeTracePayloads.sanitize(null, 10);
        assertNull(s.text());
        assertFalse(s.truncated());
    }

    @Test
    void attachSetsBothFieldsAndFlags() {
        NodeTraceEntry e = NodeTracePayloads.attach(entry(), "in", "x".repeat(40), 16, 0);
        assertEquals("in", e.inputJson());
        assertEquals(Boolean.FALSE, e.inputTruncated());
        assertEquals(16, e.outputJson().length());
        assertEquals(Boolean.TRUE, e.outputTruncated());
    }

    @Test
    void exhaustedRunBudgetStoresNoPayloadButFlagsIt() {
        NodeTraceEntry e =
                NodeTracePayloads.attach(
                        entry(),
                        "input",
                        "output",
                        NodeTracePayloads.MAX_FIELD_CHARS,
                        NodeTracePayloads.MAX_RUN_PAYLOAD_CHARS);
        assertNull(e.inputJson());
        assertNull(e.outputJson());
        assertEquals(Boolean.TRUE, e.inputTruncated());
        assertEquals(Boolean.TRUE, e.outputTruncated());
    }

    @Test
    void runBudgetIsSharedBetweenInputAndOutput() {
        long used = NodeTracePayloads.MAX_RUN_PAYLOAD_CHARS - 10;
        NodeTraceEntry e =
                NodeTracePayloads.attach(
                        entry(), "123456", "abcdefgh", NodeTracePayloads.MAX_FIELD_CHARS, used);
        assertEquals("123456", e.inputJson());
        assertEquals("abcd", e.outputJson());
        assertEquals(Boolean.TRUE, e.outputTruncated());
    }

    @Test
    void payloadCharsUsedSumsStoredPayloads() {
        NodeTraceEntry a = NodeTracePayloads.attach(entry(), "12345", "123", 100, 0);
        NodeTraceEntry b = entry();
        assertEquals(8, NodeTracePayloads.payloadCharsUsed(List.of(a, b)));
    }

    // --- redaction --------------------------------------------------------

    @Test
    void redactsCredentialJsonFieldsAtAnyDepth() {
        String out =
                NodeTracePayloads.sanitize(
                                "{\"user\":\"ann\",\"auth\":{\"password\":\"hunter2hunter2\",\"apiKey\":\"abc123def456\"}}",
                                1000)
                        .text();
        assertFalse(out.contains("hunter2hunter2"), out);
        assertFalse(out.contains("abc123def456"), out);
        assertTrue(out.contains("ann"), out);
    }

    @Test
    void redactsKnownKeyShapesInProse() {
        String openAi = "sk-" + "A".repeat(30);
        String github = "ghp_" + "b".repeat(36);
        String out =
                NodeTracePayloads.sanitize("use " + openAi + " and " + github + " please", 1000)
                        .text();
        assertFalse(out.contains(openAi), out);
        assertFalse(out.contains(github), out);
        assertTrue(out.startsWith("use "), out);
    }

    @Test
    void redactsBearerTokensButNotProse() {
        String out =
                NodeTracePayloads.sanitize(
                                "sent Bearer abcDEF123456xyz; basic authentication is off", 1000)
                        .text();
        assertFalse(out.contains("abcDEF123456xyz"), out);
        assertTrue(out.contains("basic authentication is off"), out);
    }

    @Test
    void redactsKeyValuePairsInQueryStringsAndHeaders() {
        String out =
                NodeTracePayloads.sanitize(
                                "GET /x?user=bob&access_token=tok998877&page=2\nX-Api-Key: zz11yy22xx33",
                                1000)
                        .text();
        assertFalse(out.contains("tok998877"), out);
        assertFalse(out.contains("zz11yy22xx33"), out);
        assertTrue(out.contains("user=bob"), out);
        assertTrue(out.contains("page=2"), out);
    }

    @Test
    void redactsSecretFieldInJsonTooLargeOrCutToParse() {
        // Unterminated JSON (as after a cut) is not parseable, so only the
        // text-shape pass can catch the field.
        String out =
                NodeTracePayloads.sanitize("{\"items\":[1,2],\"client_secret\":\"s3cr3tvalue", 1000)
                        .text();
        assertFalse(out.contains("s3cr3tvalue"), out);
    }

    @Test
    void redactionHappensBeforeTruncationSoACutSecretDoesNotLeak() {
        String secret = "sk-" + "Q".repeat(40);
        String raw = "x".repeat(20) + secret;
        String out = NodeTracePayloads.sanitize(raw, 30).text();
        assertFalse(out.contains("QQQQQQQQQQ"), out);
    }

    @Test
    void jwtIsRedacted() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop";
        String out = NodeTracePayloads.sanitize("token for you: " + jwt, 1000).text();
        assertFalse(out.contains(jwt), out);
    }

    // --- NodeTraceEntry contract -----------------------------------------

    @Test
    void durationIsDerivedFromTimestamps() {
        assertEquals(1500L, entry().durationMs());
    }

    @Test
    void preExistingTraceJsonStillDeserializes() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String legacy =
                "[{\"nodeId\":\"n1\",\"nodeType\":\"code\",\"label\":\"T\",\"status\":\"OK\","
                        + "\"summary\":\"s\",\"startedAt\":\"2026-09-01T00:00:00Z\",\"completedAt\":\"2026-09-01T00:00:02Z\","
                        + "\"logLines\":[\"a\"]}]";
        List<NodeTraceEntry> trace =
                mapper.readValue(
                        legacy,
                        mapper.getTypeFactory()
                                .constructCollectionType(List.class, NodeTraceEntry.class));
        NodeTraceEntry e = trace.get(0);
        assertEquals("n1", e.nodeId());
        assertEquals(2000L, e.durationMs());
        assertNull(e.inputJson());
        assertNull(e.tokensIn());
    }

    @Test
    void newFieldsRoundTripUnderTheirWireNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        NodeTraceEntry e =
                NodeTracePayloads.attach(entry(), "in", "out", 100, 0)
                        .withUsage(120L, 30L, new BigDecimal("0.0042"));
        String json = mapper.writeValueAsString(e);
        for (String field :
                List.of(
                        "\"durationMs\":1500",
                        "\"inputJson\":\"in\"",
                        "\"outputJson\":\"out\"",
                        "\"inputTruncated\":false",
                        "\"outputTruncated\":false",
                        "\"tokensIn\":120",
                        "\"tokensOut\":30",
                        "\"costUsd\":0.0042")) {
            assertTrue(json.contains(field), field + " missing from " + json);
        }
        assertEquals(e, mapper.readValue(json, NodeTraceEntry.class));
    }

    @Test
    void redactionOfLongKeywordFreeRunIsLinear() {
        // The key/value pattern used to backtrack super-quadratically on a long alphanumeric run.
        String hostile = "a".repeat(30_000);
        long t = System.nanoTime();
        assertEquals(hostile, NodeTracePayloads.redact(hostile));
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 2_000, "redact took " + ms + " ms");
    }

    @Test
    void redactionOfRepeatedKeywordWithoutSeparatorIsLinear() {
        String hostile = "token".repeat(40_000);
        long t = System.nanoTime();
        NodeTracePayloads.redact(hostile);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 2_000, "redact took " + ms + " ms");
    }

    @Test
    void keyValueRedactionStillMasksPrefixedAndSuffixedKeys() {
        assertEquals(
                "my_api_key_v2=" + NodeTracePayloads.REDACTED,
                NodeTracePayloads.redact("my_api_key_v2=abc123"));
        assertEquals(
                "\"db_password\": \"" + NodeTracePayloads.REDACTED + "\"",
                NodeTracePayloads.redact("\"db_password\": \"hunter2\""));
    }
}
