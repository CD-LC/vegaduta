package com.agenticai.core.agents.tools.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The callback against a stubbed desktop-agent: schema honesty, body forwarding, agent errors
 * becoming tool-visible text, and confirmation summaries that show the exact GUI action.
 */
class DesktopToolCallbackTest {

    private HttpServer server;
    private DesktopAgentClient client;
    private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    private volatile String reply = "{\"ok\":true}";
    private volatile int replyStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(replyStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new DesktopAgentClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "s3cret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DesktopToolCallback tool(DesktopToolCallback.Action action, boolean approval) {
        return new DesktopToolCallback(client, action, approval);
    }

    private JsonNode result(DesktopToolCallback t, String input) throws IOException {
        return JsonMappers.plain().readTree(t.call(input));
    }

    @Test
    void everyActionIsAToolWithANameAndSchema() throws Exception {
        for (DesktopToolCallback.Action a : DesktopToolCallback.Action.values()) {
            var def = tool(a, false).getToolDefinition();
            assertNotNull(def.name(), a::name);
            assertTrue(def.name().startsWith("local_desktop_"), def.name());
            assertTrue(def.description().length() > 20, def.name());
            // The benign tools never mention approval; gated ones do only while the pin holds.
            assertEquals(!a.benign && false, def.description().contains("approval"), def.name());
        }
        var gated = tool(DesktopToolCallback.Action.CLICK, true).getToolDefinition();
        assertTrue(gated.description().contains("approval"));
    }

    @Test
    void clickForwardsXYAndBearer() throws Exception {
        JsonNode r = result(
                tool(DesktopToolCallback.Action.CLICK, false),
                "{\"x\":120,\"y\":340,\"button\":\"right\"}");
        assertTrue(r.path("ok").asBoolean());
        assertEquals("POST /click auth=Bearer s3cret", requests.poll());
    }

    @Test
    void clipboardGetReadsAndSetWrites() throws Exception {
        result(tool(DesktopToolCallback.Action.CLIPBOARD, false), "{}");
        assertEquals("GET /clipboard auth=Bearer s3cret", requests.poll());
        result(tool(DesktopToolCallback.Action.CLIPBOARD, false), "{\"text\":\"hi\"}");
        assertEquals("POST /clipboard auth=Bearer s3cret", requests.poll());
    }

    @Test
    void agentErrorsComeBackAsTextNotExceptions() throws Exception {
        reply = "{\"error\":\"no UI element labelled \\\"Save\\\"\"}";
        replyStatus = 404;
        JsonNode r = result(tool(DesktopToolCallback.Action.PRESS, false), "{\"label\":\"Save\"}");
        assertTrue(r.path("error").asText().contains("Save"), r.toString());
    }

    @Test
    void unreachableAgentExplainsHowToStartIt() throws Exception {
        stop();
        JsonNode r = result(tool(DesktopToolCallback.Action.CLICK, false), "{\"x\":1,\"y\":2}");
        assertTrue(r.path("error").asText().contains("desktop-agent"), r.toString());
    }

    @Test
    void confirmationSummariesShowTheExactAction() {
        DesktopToolCallback click = tool(DesktopToolCallback.Action.CLICK, true);
        String s = click.describeForConfirmation("{\"x\":50,\"y\":60}");
        assertTrue(s.contains("50") && s.contains("60"), s);

        DesktopToolCallback type = tool(DesktopToolCallback.Action.TYPE, true);
        assertTrue(type.describeForConfirmation("{\"text\":\"hello world\"}").contains("hello world"));

        DesktopToolCallback press = tool(DesktopToolCallback.Action.PRESS, true);
        assertTrue(press.describeForConfirmation("{\"label\":\"Send\"}").contains("Send"));

        DesktopToolCallback screen = tool(DesktopToolCallback.Action.SCREEN, true);
        assertNull(screen.describeForConfirmation("{}"));
    }
}
