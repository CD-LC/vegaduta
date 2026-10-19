package io.vegaduta.agent.core.classification;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Turns a {@link Governed} annotation on a method or class into a {@link ToolClassification}. */
public final class GovernedAnnotations {

  private GovernedAnnotations() {}

  /**
   * The classification for {@code method}, from its own annotation or its declaring class's.
   *
   * @param method the tool method
   * @param toolName the name under which the tool is offered to the model
   */
  public static Optional<ToolClassification> classify(Method method, String toolName) {
    Governed governed = method.getAnnotation(Governed.class);
    if (governed == null) {
      governed = method.getDeclaringClass().getAnnotation(Governed.class);
    }
    return governed == null ? Optional.empty() : Optional.of(toClassification(governed, toolName));
  }

  public static ToolClassification toClassification(Governed governed, String toolName) {
    return ToolClassification.builder(toolName)
        .risk(governed.risk())
        .sideEffecting(governed.sideEffecting())
        .readsPrivateData(governed.readsPrivateData())
        .consumesUntrustedInput(governed.untrustedInput())
        .exfiltrationSink(governed.exfiltrationSink())
        .idempotent(governed.idempotent())
        .requiresApproval(governed.requiresApproval())
        .risks(Set.of(governed.risks()))
        .build();
  }

  /** The annotation on the method, or on its class, or {@code null}. */
  public static @Nullable Governed find(Method method) {
    Governed governed = method.getAnnotation(Governed.class);
    return governed != null ? governed : method.getDeclaringClass().getAnnotation(Governed.class);
  }
}
