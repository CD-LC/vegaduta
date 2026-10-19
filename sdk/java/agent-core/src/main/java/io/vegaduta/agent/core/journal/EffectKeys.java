package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.tool.ToolCall;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds effect keys: SHA-256 over run id, tool name and the trimmed arguments. Two calls in the
 * same run with the same tool and arguments share a key and therefore a journaled result.
 */
public final class EffectKeys {

  private EffectKeys() {}

  public static String key(RunContext context, ToolCall call) {
    return key(context.runId(), call.toolName(), call.argumentsJson());
  }

  public static String key(String runId, String toolName, String argumentsJson) {
    String material = runId + '\u0000' + toolName + '\u0000' + argumentsJson.strip();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
    }
  }
}
