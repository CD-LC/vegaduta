package io.vegaduta.agent.autoconfigure;

import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.core.classification.StaticClassificationSource;
import io.vegaduta.agent.springai.GovernedMethodScanner;
import io.vegaduta.agent.springai.ToolGovernor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * Makes governance out of the box: every bean is scanned for {@code @Tool} methods carrying
 * {@code @Governed} (their classifications are registered), and every {@link ToolCallback} or
 * {@link ToolCallbackProvider} bean is replaced by its governed wrapper.
 */
public final class GovernedToolBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

  private static final Logger log = LoggerFactory.getLogger(GovernedToolBeanPostProcessor.class);

  private final ObjectProvider<StaticClassificationSource> classifications;
  private final ObjectProvider<ToolGovernor> governor;

  public GovernedToolBeanPostProcessor(
      ObjectProvider<StaticClassificationSource> classifications,
      ObjectProvider<ToolGovernor> governor) {
    this.classifications = classifications;
    this.governor = governor;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof ToolGovernor || bean instanceof StaticClassificationSource) {
      return bean;
    }
    List<ToolClassification> found = GovernedMethodScanner.scan(bean);
    if (!found.isEmpty()) {
      StaticClassificationSource source = classifications.getIfAvailable();
      if (source != null) {
        found.forEach(source::register);
        log.debug(
            "Registered {} governed tool classification(s) from bean '{}'", found.size(), beanName);
      }
    }
    if (bean instanceof ToolCallback callback) {
      ToolGovernor g = governor.getIfAvailable();
      return g == null ? bean : g.govern(callback);
    }
    if (bean instanceof ToolCallbackProvider provider) {
      ToolGovernor g = governor.getIfAvailable();
      return g == null ? bean : g.govern(provider);
    }
    return bean;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
