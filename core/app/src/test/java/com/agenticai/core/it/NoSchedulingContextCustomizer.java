package com.agenticai.core.it;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Removes the post-processor {@code @EnableScheduling} registers, so no {@code @Scheduled} job
 * fires inside a test context. CoreApplication opts scheduling in directly, so the {@code
 * spring.task.scheduling.enabled} property cannot stop it; a background poll's statements land
 * inside {@link HotPathQueryBudgetIT}'s measured window and flake the recorded budget.
 */
public class NoSchedulingContextCustomizer implements ContextCustomizerFactory {

    // The framework's own constant: Spring 7 renamed the bean from
    // org.springframework.context.annotation.internalScheduledAnnotationProcessor, and a hard-coded
    // old name silently stopped removing it (scheduled jobs then fired inside the measured window).
    private static final String PROCESSOR =
            TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return (ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) ->
                context.addBeanFactoryPostProcessor(
                        beanFactory -> {
                            if (beanFactory
                                            instanceof
                                            org.springframework.beans.factory.support
                                                            .BeanDefinitionRegistry
                                                    registry
                                    && registry.containsBeanDefinition(PROCESSOR)) {
                                registry.removeBeanDefinition(PROCESSOR);
                            }
                        });
    }
}
