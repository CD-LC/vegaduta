package io.vegaduta.agent.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.hook.FailMode;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookHookTest {

  private HttpServer server;
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastSignature = new AtomicReference<>();
  private volatile String reply = "{\"decision\":\"continue\"}";
  private volatile int status = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/policy",
        exchange -> {
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          lastSignature.set(exchange.getRequestHeaders().getFirst(WebhookHook.SIGNATURE_HEADER));
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private URI endpoint() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/policy");
  }

  private static HookEvent event() {
    return new HookEvent(
        HookPoint.BEFORE_TOOL_CALL,
        RunContext.builder().runId("r1").tenantId("t1").build(),
        "send_email",
        "{\"to\":\"x@y\"}",
        Map.of("extra", "v"));
  }

  @Test
  void postsSignedJsonAndParsesBlock() {
    reply = "{\"decision\":\"block\",\"reason\":\"after hours\"}";
    WebhookHook hook = new WebhookHook(endpoint(), "s3cret", Duration.ofSeconds(5));

    HookDecision decision = hook.apply(event());

    assertThat(decision).isEqualTo(HookDecision.block("after hours"));
    assertThat(lastBody.get())
        .contains("\"point\":\"BEFORE_TOOL_CALL\"")
        .contains("\"toolName\":\"send_email\"")
        .contains("\"payload\":\"{\\\"to\\\":\\\"x@y\\\"}\"")
        .contains("\"extra\":\"v\"");
    assertThat(lastSignature.get())
        .isEqualTo(WebhookHook.sign(lastBody.get(), "s3cret".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void parsesReplaceAndContinue() {
    reply = "{\"decision\":\"replace\",\"payload\":\"{\\\"to\\\":\\\"safe@y\\\"}\"}";
    WebhookHook hook = new WebhookHook(endpoint(), null, Duration.ofSeconds(5));
    assertThat(hook.apply(event())).isEqualTo(HookDecision.replace("{\"to\":\"safe@y\"}"));
    assertThat(lastSignature.get()).isNull();

    reply = "{}";
    assertThat(hook.apply(event())).isEqualTo(HookDecision.CONTINUE);
  }

  @Test
  void engineAppliesFailMode() {
    status = 500;
    WebhookHook hook = new WebhookHook(endpoint(), null, Duration.ofSeconds(5));
    HookEngine open =
        new HookEngine()
            .register(
                new HookRegistration(
                    "w", Set.of(HookPoint.BEFORE_TOOL_CALL), "*", hook, FailMode.OPEN));
    HookEngine closed =
        new HookEngine()
            .register(
                new HookRegistration(
                    "w", Set.of(HookPoint.BEFORE_TOOL_CALL), "*", hook, FailMode.CLOSED));

    assertThat(open.run(event())).isEqualTo(HookDecision.CONTINUE);
    assertThat(closed.run(event())).isEqualTo(HookDecision.block("hook_failed:w"));
  }

  @Test
  void globsMatchToolNames() {
    assertThat(Globs.matches("*", "anything")).isTrue();
    assertThat(Globs.matches("send_*", "send_email")).isTrue();
    assertThat(Globs.matches("send_*", "read_email")).isFalse();
    assertThat(Globs.matches("a?c", "abc")).isTrue();
    assertThat(Globs.matches("a?c", "abbc")).isFalse();
  }

  @Test
  void jsonLiteRoundTrips() {
    String json = JsonLite.writeObject(Map.of("k", "line\n\"quoted\" \\ end"));
    assertThat(JsonLite.readStringField(json, "k")).isEqualTo("line\n\"quoted\" \\ end");
    assertThat(JsonLite.readStringField(json, "missing")).isNull();
  }
}
