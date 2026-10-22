package com.agenticai.core.chat.structured;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * OpenAI strict function calling: schema eligibility, body rewriting, and the blocking + streaming
 * HTTP wiring.
 */
class ToolStrictModeTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    static final String STRICT_PARAMS =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"properties\":{\"city\":{\"type\":\"string\"},\"days\":{\"type\":\"integer\"}},"
                + "\"required\":[\"city\",\"days\"],\"additionalProperties\":false}";
    static final String OPEN_PARAMS =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}";

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------- eligibility

    @Test
    void closedAllRequiredObjectIsEligible() {
        assertThat(ToolStrictMode.eligible(json(STRICT_PARAMS))).isTrue();
        // an already-closed no-argument tool qualifies
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")))
                .isTrue();
    }

    @Test
    void openOrPartiallyRequiredOrNonObjectSchemasAreNotEligible() {
        assertThat(ToolStrictMode.eligible(json(OPEN_PARAMS)))
                .isFalse(); // no additionalProperties:false
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"string\"}},"
                                            + "\"required\":[\"a\"],\"additionalProperties\":false}")))
                .isFalse(); // b optional
        assertThat(ToolStrictMode.eligible(json("{\"type\":\"object\",\"properties\":{}}")))
                .isFalse(); // open no-arg
        assertThat(
                        ToolStrictMode.eligible(
                                json("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")))
                .isFalse();
        assertThat(ToolStrictMode.eligible(json("{\"properties\":{}}"))).isFalse(); // no type
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"anyOf\":[{\"type\":\"object\"}],\"additionalProperties\":false}")))
                .isFalse();
        assertThat(ToolStrictMode.eligible(null)).isFalse();
        assertThat(ToolStrictMode.eligible(json("\"x\""))).isFalse();
    }

    @Test
    void nestedOpenObjectDisqualifies() {
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"properties\":{\"o\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},"
                                            + "\"required\":[\"x\"]}},\"required\":[\"o\"],\"additionalProperties\":false}")))
                .isFalse();
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"properties\":{\"o\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}},"
                                            + "\"required\":[\"x\"],\"additionalProperties\":false}},\"required\":[\"o\"],\"additionalProperties\":false}")))
                .isTrue();
    }

    @Test
    void unsupportedKeywordsDisqualifyEvenWhenTheObjectIsClosed() {
        for (String kw :
                new String[] {
                    "\"minLength\":1",
                    "\"maxLength\":9",
                    "\"pattern\":\"^a\"",
                    "\"format\":\"email\"",
                    "\"minimum\":0",
                    "\"default\":\"x\"",
                    "\"minItems\":1",
                    "\"uniqueItems\":true"
                }) {
            String s =
                    "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\","
                            + kw
                            + "}},"
                            + "\"required\":[\"a\"],\"additionalProperties\":false}";
            assertThat(ToolStrictMode.eligible(json(s))).as(kw).isFalse();
        }
        assertThat(
                        ToolStrictMode.eligible(
                                json(
                                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"],"
                                            + "\"additionalProperties\":false,\"patternProperties\":{}}")))
                .isFalse();
    }

    @Test
    void aPropertyNamedLikeAKeywordIsJustAName() {
        String s =
                "{\"type\":\"object\",\"properties\":{\"default\":{\"type\":\"string\"},\"format\":{\"type\":\"string\"}},"
                    + "\"required\":[\"default\",\"format\"],\"additionalProperties\":false}";
        assertThat(ToolStrictMode.eligible(json(s))).isTrue();
    }

    // ----------------------------------------------------------------- rewrite

    private static String body(String... toolParams) {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < toolParams.length; i++) {
            if (i > 0) {
                tools.append(',');
            }
            tools.append("{\"type\":\"function\",\"function\":{\"name\":\"t")
                    .append(i)
                    .append("\",\"description\":\"d\",\"parameters\":")
                    .append(toolParams[i])
                    .append("}}");
        }
        return "{\"model\":\"gpt-4o\",\"temperature\":0.2,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":["
                + tools
                + "]}";
    }

    @Test
    void onlyEligibleToolsGetStrictAndTheRestOfTheBodyIsKept() throws Exception {
        byte[] in = body(STRICT_PARAMS, OPEN_PARAMS).getBytes(StandardCharsets.UTF_8);
        byte[] out = ToolStrictMode.rewrite(in);
        assertThat(out).isNotSameAs(in);
        JsonNode root = MAPPER.readTree(out);
        JsonNode t0 = root.get("tools").get(0).get("function");
        JsonNode t1 = root.get("tools").get(1).get("function");
        assertThat(t0.get("strict").asBoolean()).isTrue();
        assertThat(t0.get("parameters").has("$schema")).isFalse();
        assertThat(t1.has("strict")).isFalse();
        assertThat(t1.get("parameters")).isEqualTo(json(OPEN_PARAMS));
        assertThat(root.get("model").asString()).isEqualTo("gpt-4o");
        assertThat(root.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(root.get("messages").get(0).get("content").asString()).isEqualTo("hi");
    }

    @Test
    void bodyWithNothingToChangeIsReturnedAsTheSameBytes() {
        for (String b :
                new String[] {
                    body(OPEN_PARAMS), // no eligible tool
                    "{\"model\":\"gpt-4o\",\"messages\":[]}", // no tools at all
                    "{\"tools\":[]}",
                    "[1,2]",
                    "not json at all \"tools\"",
                    body(STRICT_PARAMS).replace("\"parameters\"", "\"strict\":false,\"parameters\"")
                }) { // explicit strict kept
            byte[] in = b.getBytes(StandardCharsets.UTF_8);
            assertThat(ToolStrictMode.rewrite(in)).as(b).isSameAs(in);
        }
        assertThat(ToolStrictMode.rewrite(null)).isNull();
        byte[] empty = new byte[0];
        assertThat(ToolStrictMode.rewrite(empty)).isSameAs(empty);
    }

    @Test
    void nonFunctionToolsAreNotTouched() throws Exception {
        String b =
                "{\"tools\":[{\"type\":\"web_search\",\"function\":{\"name\":\"x\",\"parameters\":"
                        + STRICT_PARAMS
                        + "}}]}";
        byte[] in = b.getBytes(StandardCharsets.UTF_8);
        assertThat(ToolStrictMode.rewrite(in)).isSameAs(in);
    }

    @Test
    void rewriteDoesNotMutateTheInputBytes() {
        byte[] in = body(STRICT_PARAMS).getBytes(StandardCharsets.UTF_8);
        byte[] copy = in.clone();
        ToolStrictMode.rewrite(in);
        assertThat(in).isEqualTo(copy);
    }

    // --------------------------------------------------------------- HTTP wiring

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<Long> declaredLengths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/echo",
                exchange -> {
                    String cl = exchange.getRequestHeaders().getFirst("Content-Length");
                    declaredLengths.add(cl == null ? -1L : Long.parseLong(cl));
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
    }

    private static Map<String, Object> requestMap(String params) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = MAPPER.readValue(body(params), Map.class);
        return m;
    }

    @Test
    void blockingPathSendsStrictAndAConsistentContentLength() throws Exception {
        RestClient client =
                RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory())
                        .requestInterceptor(ToolStrictMode.restInterceptor())
                        .build();
        client.post()
                .uri(url())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestMap(STRICT_PARAMS))
                .retrieve()
                .toBodilessEntity();

        assertThat(bodies).hasSize(1);
        JsonNode sent = MAPPER.readTree(bodies.get(0));
        assertThat(sent.get("tools").get(0).get("function").get("strict").asBoolean()).isTrue();
        assertThat(declaredLengths.get(0))
                .isIn(-1L, (long) bodies.get(0).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void blockingPathLeavesIneligibleRequestsExactlyAsSerialized() throws Exception {
        RestClient plain =
                RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
        RestClient strict =
                RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory())
                        .requestInterceptor(ToolStrictMode.restInterceptor())
                        .build();
        plain.post()
                .uri(url())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestMap(OPEN_PARAMS))
                .retrieve()
                .toBodilessEntity();
        strict.post()
                .uri(url())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestMap(OPEN_PARAMS))
                .retrieve()
                .toBodilessEntity();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
    }

    @Test
    void streamingPathSendsStrictAndAConsistentContentLength() throws Exception {
        WebClient client =
                WebClient.builder()
                        .clientConnector(new JdkClientHttpConnector())
                        .filter(ToolStrictMode.webFilter())
                        .build();
        String reply =
                client.post()
                        .uri(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(requestMap(STRICT_PARAMS))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

        assertThat(reply).contains("ok");
        assertThat(bodies).hasSize(1);
        JsonNode sent = MAPPER.readTree(bodies.get(0));
        assertThat(sent.get("tools").get(0).get("function").get("strict").asBoolean()).isTrue();
        assertThat(sent.get("tools").get(0).get("function").get("parameters").has("$schema"))
                .isFalse();
        assertThat(declaredLengths.get(0))
                .isIn(-1L, (long) bodies.get(0).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void streamingPathLeavesIneligibleRequestsExactlyAsSerialized() throws Exception {
        WebClient plain = WebClient.builder().clientConnector(new JdkClientHttpConnector()).build();
        WebClient strict =
                WebClient.builder()
                        .clientConnector(new JdkClientHttpConnector())
                        .filter(ToolStrictMode.webFilter())
                        .build();
        plain.post()
                .uri(url())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestMap(OPEN_PARAMS))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        strict.post()
                .uri(url())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestMap(OPEN_PARAMS))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
    }
}
