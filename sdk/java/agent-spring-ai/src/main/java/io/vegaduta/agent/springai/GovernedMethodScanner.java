package io.vegaduta.agent.springai;

import io.vegaduta.agent.api.annotation.Governed;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.classification.GovernedAnnotations;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Finds {@code @Tool} methods that also carry {@link Governed} (on the method or its class) and
 * produces the classification for each, keyed by the Spring AI tool name.
 */
public final class GovernedMethodScanner {

  private GovernedMethodScanner() {}

  public static List<ToolClassification> scan(Object bean) {
    return scan(org.springframework.aop.support.AopUtils.getTargetClass(bean));
  }

  public static List<ToolClassification> scan(Class<?> type) {
    List<ToolClassification> found = new ArrayList<>();
    ReflectionUtils.doWithMethods(
        type,
        method -> {
          Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
          if (tool == null) {
            return;
          }
          Governed governed = GovernedAnnotations.find(method);
          if (governed == null) {
            return;
          }
          found.add(GovernedAnnotations.toClassification(governed, toolName(method)));
        },
        ReflectionUtils.USER_DECLARED_METHODS);
    return found;
  }

  static String toolName(Method method) {
    return ToolUtils.getToolName(method);
  }
}
