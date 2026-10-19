package com.agenticai.core.tenancy.scheduling;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.stereotype.Component;

/**
 * Deployment-shape gate for background work: one image, several shapes (api / worker / jobs), and
 * per shape a property list says which scheduled beans and startup listeners stay dormant - with no
 * edit to any of the ~30 beans involved.
 *
 * <ul>
 *   <li>{@code app.scheduling.skip-bean-classes} - classes whose {@code @Scheduled} methods are not
 *       registered (implemented by replacing the {@code @EnableScheduling} post-processor with
 *       {@link RoleFilteringScheduledAnnotationBeanPostProcessor}).
 *   <li>{@code app.scheduling.keep-bean-classes} - exceptions to the list above, so a pool can skip
 *       a broad package ({@code com.agenticai.core.*}) and keep the few pollers it owns.
 *   <li>{@code app.scheduling.pool} / {@code app.scheduling.offloaded-pools} - named pools of the
 *       jobs shape, resolved by {@link SchedulingPools} into the two lists above.
 *   <li>{@code app.scheduling.skip-listener-classes} - classes whose {@code @EventListener} methods
 *       (the {@code ApplicationReadyEvent} boot sweeps) become no-ops (implemented by {@link
 *       SkippingEventListenerFactory}).
 * </ul>
 *
 * <p><b>Default empty = today's behaviour.</b> With both lists empty this class changes nothing: it
 * registers no bean and replaces no definition, so a single-process deployment is what it was
 * before.
 *
 * <p><b>Ordering.</b> A registry post-processor with no {@code Ordered} runs after {@code
 * ConfigurationClassPostProcessor}, which is what registers {@code @EnableScheduling}'s definition
 * - so it exists by the time this runs and can be swapped before any {@code BeanPostProcessor} is
 * instantiated. If scheduling is not enabled in this shape (the worker deployable has no
 * {@code @EnableScheduling}) the bean skip list has nothing to act on and is ignored.
 */
@Component
public class SchedulingRoleRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    static final String SKIP_BEAN_CLASSES = "app.scheduling.skip-bean-classes";
    static final String KEEP_BEAN_CLASSES = "app.scheduling.keep-bean-classes";
    static final String SKIP_LISTENER_CLASSES = "app.scheduling.skip-listener-classes";
    static final String LISTENER_FACTORY_BEAN_NAME = "schedulingRoleSkippingEventListenerFactory";

    // EnvironmentAware, not constructor injection: a BeanDefinitionRegistryPostProcessor is
    // instantiated before the autowiring BeanPostProcessor exists, so a constructor parameter
    // fails with "No default constructor found" at boot (ApplicationContextAwareProcessor is
    // already active at that point and delivers the Environment).
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        String processorName = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
        SchedulingRoleMatcher scheduled = SchedulingPools.scheduledBeans(Binder.get(environment));
        if (!scheduled.isEmpty() && registry.containsBeanDefinition(processorName)) {
            RootBeanDefinition replacement =
                    new RootBeanDefinition(
                            RoleFilteringScheduledAnnotationBeanPostProcessor.class,
                            () -> new RoleFilteringScheduledAnnotationBeanPostProcessor(scheduled));
            replacement.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            // Same bean name on purpose: Boot's task-scheduler auto-configuration
            // is @ConditionalOnBean on this exact name.
            registry.removeBeanDefinition(processorName);
            registry.registerBeanDefinition(processorName, replacement);
        }

        SchedulingRoleMatcher listeners =
                SchedulingRoleMatcher.fromEnvironment(environment, SKIP_LISTENER_CLASSES);
        if (!listeners.isEmpty()) {
            RootBeanDefinition factory =
                    new RootBeanDefinition(
                            SkippingEventListenerFactory.class,
                            () -> new SkippingEventListenerFactory(listeners));
            factory.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(LISTENER_FACTORY_BEAN_NAME, factory);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        // nothing to do at this stage
    }
}
