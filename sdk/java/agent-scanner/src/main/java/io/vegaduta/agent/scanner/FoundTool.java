package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import java.lang.reflect.Method;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A tool method located by {@link ToolMethodFinder}.
 *
 * @param toolName the name the model sees: the annotation's {@code name} attribute or the method
 *     name
 * @param description the annotation's description, or {@code null} when none is declared
 * @param method the method itself
 * @param governed the method or its class carries {@code @Governed}
 * @param toolAnnotation the fully qualified name of the framework {@code @Tool} annotation found on
 *     the method, or {@code null} when the method was found by {@code @Governed} alone
 */
@Experimental
public record FoundTool(
    String toolName,
    @Nullable String description,
    Method method,
    boolean governed,
    @Nullable String toolAnnotation) {

  public FoundTool {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(method, "method");
  }

  /** {@code com.acme.BillingTools#refund}. */
  public String location() {
    return method.getDeclaringClass().getName() + "#" + method.getName();
  }
}
