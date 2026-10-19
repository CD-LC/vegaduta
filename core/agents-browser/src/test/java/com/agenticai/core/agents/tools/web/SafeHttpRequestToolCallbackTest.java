package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.tools.web.HttpRequestExecutorSafeModeTestSupport.RecordingExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The model-facing surface of the safe-mode tools: schemas, names, argument handling and the
 * failure contract.
 */
class SafeHttpRequestToolCallbackTest {

    private static final String HOST = "203.0.113.10";
    private static final String SECRET = "sk_live_9f8e7d6c5b4a3210";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpRequestPolicy base(List<String> agentHosts) {
        return new HttpRequestPolicy(
                Set.of(),
                false,
                1024,
                1_048_576,
                3,
                List.of("application/json", "text/*"),
                Set.of("authorization", "cookie", "x-api-key"),
                List.of(),
                agentHosts,
                true);
    }

    private static SafeHttpRequestToolCallback read(RecordingExecutor executor) {
        return SafeHttpRequestToolCallback.read(executor, base(List.of()));
    }

    private static SafeHttpRequestToolCallback write(RecordingExecutor executor) {
        return SafeHttpRequestToolCallback.write(executor, base(List.of()));
    }

    // ------------------------------------------------------------------ definitions

    @Test
    void theTwoToolsHaveDistinctNamesAndValidJsonSchemas() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(null);
        var readDef = read(executor).getToolDefinition();
        var writeDef = write(executor).getToolDefinition();

        assertEquals("http_request", readDef.name());
        assertEquals("http_request_write", writeDef.name());
        assertEquals(
                Set.of("http_request", "http_request_write"),
                SafeHttpRequestToolCallback.toolNames());

        JsonNode readSchema = MAPPER.readTree(readDef.inputSchema());
        JsonNode writeSchema = MAPPER.readTree(writeDef.inputSchema());
        assertEquals("url", readSchema.get("required").get(0).asText());
        assertEquals("url", writeSchema.get("required").get(0).asText());

        JsonNode readProps = readSchema.get("properties");
        JsonNode writeProps = writeSchema.get("properties");
        for (String common :
                List.of(
                        "method",
                        "url",
                        "headers",
                        "query",
                        "auth",
                        "authType",
                        "authHeader",
                        "timeoutSeconds",
                        "maxChars")) {
            assertTrue(readProps.has(common), "read tool should accept " + common);
            assertTrue(writeProps.has(common), "write tool should accept " + common);
        }
        assertFalse(readProps.has("body"), "the read tool has no body parameter");
        assertTrue(writeProps.has("body") && writeProps.has("bodyType"));
        assertEquals(
                "[\"GET\",\"HEAD\",\"OPTIONS\"]", readProps.get("method").get("enum").toString());
        assertEquals(
                "[\"POST\",\"PUT\",\"PATCH\",\"DELETE\"]",
                writeProps.get("method").get("enum").toString());
    }

    @Test
    void descriptionsAreShortAndNeverTeachTheModelTheInlineSecretSyntax() {
        RecordingExecutor executor = new RecordingExecutor(null);
        for (SafeHttpRequestToolCallback tool : List.of(read(executor), write(executor))) {
            String description = tool.getToolDefinition().description();
            assertFalse(description.contains("{{"), description);
            assertTrue(description.contains("NAME"), "tells the model to pass a secret's NAME");
            assertTrue(
                    description.length() < 700,
                    "tool schemas are token-budgeted: " + description.length());
        }
    }

    // ------------------------------------------------------------------ calls

    @Test
    void aSuccessfulReadIsFramedAsThirdPartyDataAndCarriesTheStructuredResult() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(HttpRequestExecutorSafeModeTestSupport.json("{\"temp\":21}"));

        String out =
                read(executor)
                        .call(
                                "{\"url\":\"https://"
                                        + HOST
                                        + "/weather\",\"query\":{\"city\":\"Pune\"}}");

        assertTrue(
                out.startsWith(
                        "Response of GET https://" + HOST + "/weather (third-party API DATA"),
                out);
        JsonNode json = MAPPER.readTree(out.substring(out.indexOf('\n') + 1));
        assertEquals(200, json.get("status").asInt());
        assertEquals(21, json.get("body").get("temp").asInt());
        assertEquals("/weather", executor.sent.get(0).uri().getPath());
        assertEquals("city=Pune", executor.sent.get(0).uri().getQuery());
    }

    @Test
    void theWriteToolDefaultsToPostAndSendsTheJsonBody() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(HttpRequestExecutorSafeModeTestSupport.json("{\"id\":7}"));

        String out =
                write(executor)
                        .call("{\"url\":\"https://" + HOST + "/items\",\"body\":{\"name\":\"x\"}}");

        assertTrue(out.startsWith("Response of POST "), out);
        assertEquals("POST", executor.sent.get(0).method());
        assertEquals("{\"name\":\"x\"}", executor.sent.get(0).body());
        assertEquals("application/json", executor.sent.get(0).header("content-type"));
    }

    @Test
    void theReadToolIgnoresABodyArgumentRatherThanSendingOne() {
        RecordingExecutor executor = new RecordingExecutor(null);
        executor.responses.add(HttpRequestExecutorSafeModeTestSupport.json("{}"));

        read(executor).call("{\"url\":\"https://" + HOST + "/\",\"body\":{\"a\":1}}");

        assertEquals("GET", executor.sent.get(0).method());
        assertEquals(null, executor.sent.get(0).body());
    }

    @Test
    void usingTheWrongToolForAVerbIsAPlainTextFailureNotAnException() {
        RecordingExecutor executor = new RecordingExecutor(null);

        String postOnRead =
                read(executor).call("{\"method\":\"POST\",\"url\":\"https://" + HOST + "/\"}");
        String getOnWrite =
                write(executor).call("{\"method\":\"GET\",\"url\":\"https://" + HOST + "/\"}");

        assertTrue(
                postOnRead.startsWith("Could not complete POST https://" + HOST + "/: "),
                postOnRead);
        assertTrue(postOnRead.contains("not available through this tool"), postOnRead);
        assertTrue(getOnWrite.startsWith("Could not complete GET "), getOnWrite);
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void anAuthValueThatIsTheSecretItselfIsRefusedAndNeverEchoed() {
        RecordingExecutor executor = new RecordingExecutor(null);

        String out =
                read(executor)
                        .call("{\"url\":\"https://" + HOST + "/\",\"auth\":\"sk_live_abc-123\"}");

        assertTrue(out.startsWith("Could not complete GET "), out);
        assertTrue(out.contains("NAME of a vault secret"), out);
        assertFalse(out.contains("sk_live_abc-123"), "the pasted value must not be repeated back");
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aNamedCredentialWorksEndToEndAndTheSecretNeverAppearsInTheResult() {
        RecordingExecutor executor =
                new RecordingExecutor(
                        HttpRequestExecutorSafeModeTestSupport.resolverWith("STRIPE_KEY", SECRET));
        executor.responses.add(
                HttpRequestExecutorSafeModeTestSupport.response(
                        200,
                        "application/json",
                        new ByteArrayInputStream(
                                ("{\"you_sent\":\"Bearer " + SECRET + "\"}")
                                        .getBytes(StandardCharsets.UTF_8))));
        SafeHttpRequestToolCallback tool =
                SafeHttpRequestToolCallback.read(executor, base(List.of(HOST)));

        String out =
                tool.call("{\"url\":\"https://" + HOST + "/balance\",\"auth\":\"STRIPE_KEY\"}");

        assertEquals("Bearer " + SECRET, executor.sent.get(0).header("authorization"));
        assertFalse(out.contains(SECRET), out);
        assertTrue(out.contains("[redacted:secret]"), out);
    }

    @Test
    void aCredentialWithoutAnAllowListIsAPlainTextRefusal() {
        RecordingExecutor executor =
                new RecordingExecutor(
                        HttpRequestExecutorSafeModeTestSupport.resolverWith("STRIPE_KEY", SECRET));

        String out =
                read(executor).call("{\"url\":\"https://" + HOST + "/\",\"auth\":\"STRIPE_KEY\"}");

        assertTrue(out.startsWith("Could not complete GET "), out);
        assertTrue(out.contains("allow-list"), out);
        assertFalse(out.contains(SECRET));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void aCredentialHeaderSuppliedDirectlyIsRefused() {
        RecordingExecutor executor = new RecordingExecutor(null);

        String out =
                read(executor)
                        .call(
                                "{\"url\":\"https://"
                                        + HOST
                                        + "/\",\"headers\":{\"Authorization\":\"Bearer abc123\"}}");

        assertTrue(out.contains("'Authorization' header is not allowed"), out);
        assertFalse(out.contains("abc123"));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void anInlineVaultReferenceInAToolCallIsRefused() {
        RecordingExecutor executor =
                new RecordingExecutor(
                        HttpRequestExecutorSafeModeTestSupport.resolverWith("STRIPE_KEY", SECRET));

        String out =
                read(executor)
                        .call(
                                "{\"url\":\"https://"
                                        + HOST
                                        + "/\",\"headers\":{\"X-Token\":\"{{secret:STRIPE_KEY}}\"}}");

        assertTrue(out.contains("'auth'"), out);
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void ssrfAndBadInputComeBackAsTextNeverAsExceptions() {
        RecordingExecutor executor = new RecordingExecutor(null);
        List<String> outputs = new ArrayList<>();
        outputs.add(read(executor).call("{\"url\":\"https://169.254.169.254/latest/meta-data/\"}"));
        outputs.add(read(executor).call("{\"url\":\"https://[::ffff:127.0.0.1]/\"}"));
        outputs.add(read(executor).call("{}"));
        outputs.add(read(executor).call("not json at all"));
        for (String out : outputs) {
            assertTrue(out.startsWith("Could not complete"), out);
        }
        assertTrue(outputs.get(0).contains("private or internal network"), outputs.get(0));
        assertTrue(executor.sent.isEmpty());
    }

    @Test
    void anUnexpectedRuntimeFailureIsGenericAndLeaksNothing() {
        RecordingExecutor executor =
                new RecordingExecutor(null) {
                    @Override
                    protected HttpRequestExecutor.RawResponse send(
                            URI uri,
                            String method,
                            Map<String, String> headers,
                            String body,
                            Duration timeout) {
                        throw new IllegalStateException("boom with internal detail " + SECRET);
                    }
                };

        String out = read(executor).call("{\"url\":\"https://" + HOST + "/\"}");

        assertTrue(out.contains("unexpected error"), out);
        assertFalse(out.contains("internal detail"), out);
        assertFalse(out.contains(SECRET), out);
    }
}
