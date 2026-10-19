package io.vegaduta.agent.core.classification;

import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A mutable map of classifications by tool name. Thread-safe. */
public final class StaticClassificationSource implements ToolClassificationSource {

  private final Map<String, ToolClassification> byName = new ConcurrentHashMap<>();

  public StaticClassificationSource() {}

  public StaticClassificationSource(Collection<ToolClassification> classifications) {
    classifications.forEach(this::register);
  }

  public StaticClassificationSource register(ToolClassification classification) {
    byName.put(classification.toolName(), classification);
    return this;
  }

  public StaticClassificationSource remove(String toolName) {
    byName.remove(toolName);
    return this;
  }

  public int size() {
    return byName.size();
  }

  @Override
  public Optional<ToolClassification> classify(String toolName) {
    return Optional.ofNullable(byName.get(toolName));
  }
}
