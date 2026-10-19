package io.vegaduta.agent.api.hook;

import io.vegaduta.agent.api.RunContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a hook sees.
 *
 * @param point where in the pipeline this is
 * @param context the run
 * @param toolName the tool involved, or {@code null} for model-call points
 * @param payload the content at this point: tool arguments before a call, tool output after it, the
 *     prompt before a model call; may be empty
 * @param attributes extra data (verdict reason, approval id, violation codes)
 */
public record HookEvent(
    HookPoint point,
    RunContext context,
    @Nullable String toolName,
    String payload,
    Map<String, String> attributes) {

  public HookEvent {
    Objects.requireNonNull(point, "point");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(payload, "payload");
    attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }
}
