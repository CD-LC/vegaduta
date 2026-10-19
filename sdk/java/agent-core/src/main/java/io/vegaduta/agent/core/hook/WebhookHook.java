package io.vegaduta.agent.core.hook;

import io.vegaduta.agent.api.hook.Hook;
import io.vegaduta.agent.api.hook.HookDecision;
import io.vegaduta.agent.api.hook.HookEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Posts the event to an HTTP endpoint and turns the reply into a decision. This is how an external
 * policy engine (a "guardian" in Agent Control Standard terms) participates.
 *
 * <p>Request body: a flat JSON object with {@code point, runId, tenantId, principal, agentId,
 * sessionId, toolName, payload} and every event attribute. When a secret is configured, the body is
 * signed with HMAC-SHA256 and the hex digest sent in {@code X-Vegaduta-Signature}.
 *
 * <p>Reply: {@code {"decision":"continue"}}, {@code {"decision":"block","reason":"..."}} or {@code
 * {"decision":"replace","payload":"..."}}. Any non-2xx status is a failure handled by the
 * registration's {@link io.vegaduta.agent.api.hook.FailMode}.
 */
public final class WebhookHook implements Hook {

  public static final String SIGNATURE_HEADER = "X-Vegaduta-Signature";

  private final HttpClient client;
  private final URI endpoint;
  private final @Nullable byte[] secret;
  private final Duration timeout;

  public WebhookHook(URI endpoint, @Nullable String secret, Duration timeout) {
    this(
        HttpClient.newBuilder().connectTimeout(timeout).build(),
        endpoint,
        secret == null ? null : secret.getBytes(StandardCharsets.UTF_8),
        timeout);
  }

  public WebhookHook(HttpClient client, URI endpoint, @Nullable byte[] secret, Duration timeout) {
    this.client = client;
    this.endpoint = endpoint;
    this.secret = secret == null ? null : secret.clone();
    this.timeout = timeout;
  }

  @Override
  public HookDecision apply(HookEvent event) {
    String body = JsonLite.writeObject(toFields(event));
    HttpRequest.Builder request =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (secret != null) {
      request.header(SIGNATURE_HEADER, sign(body, secret));
    }
    HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Webhook " + endpoint + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Webhook " + endpoint + " interrupted", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Webhook " + endpoint + " returned HTTP " + response.statusCode());
    }
    return parse(response.body());
  }

  static HookDecision parse(String json) {
    String decision = JsonLite.readStringField(json, "decision");
    if (decision == null || decision.equalsIgnoreCase("continue")) {
      return HookDecision.CONTINUE;
    }
    if (decision.equalsIgnoreCase("block")) {
      String reason = JsonLite.readStringField(json, "reason");
      return HookDecision.block(reason == null ? "blocked_by_webhook" : reason);
    }
    if (decision.equalsIgnoreCase("replace")) {
      String payload = JsonLite.readStringField(json, "payload");
      return payload == null ? HookDecision.CONTINUE : HookDecision.replace(payload);
    }
    throw new IllegalStateException("Webhook returned unknown decision '" + decision + "'");
  }

  static Map<String, String> toFields(HookEvent event) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("point", event.point().name());
    fields.put("runId", event.context().runId());
    putIfPresent(fields, "tenantId", event.context().tenantId());
    putIfPresent(fields, "principal", event.context().principal());
    putIfPresent(fields, "agentId", event.context().agentId());
    putIfPresent(fields, "sessionId", event.context().sessionId());
    putIfPresent(fields, "toolName", event.toolName());
    fields.put("payload", event.payload());
    fields.putAll(event.attributes());
    return fields;
  }

  static String sign(String body, byte[] key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is mandatory in every JDK", e);
    }
  }

  private static void putIfPresent(Map<String, String> map, String key, @Nullable String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
