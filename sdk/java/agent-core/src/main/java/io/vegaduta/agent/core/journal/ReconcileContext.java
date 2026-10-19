package io.vegaduta.agent.core.journal;

import io.vegaduta.agent.api.Experimental;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the reconciler knows about an interrupted call.
 *
 * <p>Effect keys are a SHA-256 over run id, tool name and arguments ({@link EffectKeys}), so the
 * parts cannot be recovered from the key alone. They are present only when the host described the
 * call to the {@link ReconcilingEffectJournal} ({@link ReconcilingEffectJournal#describe}) before
 * or while it ran, for example from a {@code BEFORE_TOOL_CALL} hook.
 *
 * @param key the effect key
 * @param since when {@code begin(key)} was observed
 * @param runId the run the call belonged to, or {@code null} when not described
 * @param toolName the tool that was called, or {@code null} when not described
 * @param argumentsJson the arguments it was called with, or {@code null} when not described
 */
@Experimental
public record ReconcileContext(
    String key,
    Instant since,
    @Nullable String runId,
    @Nullable String toolName,
    @Nullable String argumentsJson) {

  public ReconcileContext {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(since, "since");
  }

  /** A context with the key and begin time only. */
  public static ReconcileContext of(String key, Instant since) {
    return new ReconcileContext(key, since, null, null, null);
  }

  /** Whether the host described the tool call behind this key. */
  public boolean described() {
    return toolName != null;
  }
}
