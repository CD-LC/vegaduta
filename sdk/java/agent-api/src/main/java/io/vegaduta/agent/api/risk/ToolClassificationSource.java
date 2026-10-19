package io.vegaduta.agent.api.risk;

import java.util.Optional;

/**
 * Where classifications come from: annotations on tool code, a static map, a database, a remote
 * registry. Sources compose; the first one with an answer wins.
 */
@FunctionalInterface
public interface ToolClassificationSource {

  Optional<ToolClassification> classify(String toolName);

  /** A source that knows nothing. */
  static ToolClassificationSource empty() {
    return toolName -> Optional.empty();
  }
}
