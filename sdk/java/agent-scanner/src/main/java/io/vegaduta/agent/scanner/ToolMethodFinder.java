package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.annotation.Governed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds tool methods on classes: methods annotated with {@link Governed} (directly or through their
 * class) and methods carrying any annotation whose simple name is {@code Tool}, which covers Spring
 * AI's {@code org.springframework.ai.tool.annotation.Tool} and LangChain4j's {@code
 * dev.langchain4j.agent.tool.Tool} without depending on either library.
 *
 * <p>The tool name is the annotation's {@code name} attribute when it is a non-blank string, else
 * the method name. The description is the annotation's {@code description} attribute, or its {@code
 * value} attribute (LangChain4j uses a {@code String[]} there), when present.
 */
@Experimental
public final class ToolMethodFinder {

  private static final Logger log = LoggerFactory.getLogger(ToolMethodFinder.class);
  private static final String TOOL_SIMPLE_NAME = "Tool";

  /** Every tool method on the given classes, ordered by class name then method name. */
  public List<FoundTool> find(Collection<Class<?>> classes) {
    List<FoundTool> found = new ArrayList<>();
    for (Class<?> type : classes) {
      if (type.isAnnotation()) {
        continue;
      }
      Method[] methods;
      try {
        // Reflection can throw LinkageError for a type whose signatures reference classes that are
        // absent from the scanner's classpath; skip such a type rather than abort the scan.
        methods = type.getDeclaredMethods();
        if (type.isInterface() && Arrays.stream(methods).noneMatch(Method::isDefault)) {
          continue;
        }
      } catch (Throwable t) {
        log.debug("Skipping {}: {}", type.getName(), t.toString());
        continue;
      }
      boolean classGoverned = type.getAnnotation(Governed.class) != null;
      for (Method method : methods) {
        if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())) {
          continue;
        }
        Annotation tool = toolAnnotation(method);
        boolean governed = classGoverned || method.getAnnotation(Governed.class) != null;
        if (tool == null && !governed) {
          continue;
        }
        found.add(
            new FoundTool(
                toolName(tool, method),
                description(tool),
                method,
                governed,
                tool == null ? null : tool.annotationType().getName()));
      }
    }
    found.sort(
        Comparator.comparing((FoundTool t) -> t.method().getDeclaringClass().getName())
            .thenComparing(t -> t.method().getName()));
    return found;
  }

  static @Nullable Annotation toolAnnotation(Method method) {
    for (Annotation a : method.getAnnotations()) {
      if (TOOL_SIMPLE_NAME.equals(a.annotationType().getSimpleName())) {
        return a;
      }
    }
    return null;
  }

  static String toolName(@Nullable Annotation tool, Method method) {
    Object name = attribute(tool, "name");
    if (name instanceof String s && !s.isBlank()) {
      return s;
    }
    return method.getName();
  }

  static @Nullable String description(@Nullable Annotation tool) {
    Object description = attribute(tool, "description");
    if (description instanceof String s && !s.isBlank()) {
      return s;
    }
    Object value = attribute(tool, "value");
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    if (value instanceof String[] parts && parts.length > 0) {
      String joined = String.join(" ", parts).trim();
      return joined.isEmpty() ? null : joined;
    }
    return null;
  }

  private static @Nullable Object attribute(@Nullable Annotation annotation, String attribute) {
    if (annotation == null) {
      return null;
    }
    try {
      Method accessor = annotation.annotationType().getMethod(attribute);
      return accessor.invoke(annotation);
    } catch (NoSuchMethodException e) {
      return null;
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.debug(
          "Cannot read {}.{}(): {}",
          annotation.annotationType().getName(),
          attribute,
          e.toString());
      return null;
    }
  }
}
