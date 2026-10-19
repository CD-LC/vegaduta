package io.vegaduta.agent.core.classification;

import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import java.util.List;
import java.util.Optional;

/** Asks each source in order; the first answer wins. */
public final class CompositeClassificationSource implements ToolClassificationSource {

  private final List<ToolClassificationSource> sources;

  public CompositeClassificationSource(List<ToolClassificationSource> sources) {
    this.sources = List.copyOf(sources);
  }

  public static CompositeClassificationSource of(ToolClassificationSource... sources) {
    return new CompositeClassificationSource(List.of(sources));
  }

  @Override
  public Optional<ToolClassification> classify(String toolName) {
    for (ToolClassificationSource source : sources) {
      Optional<ToolClassification> found = source.classify(toolName);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }
}
