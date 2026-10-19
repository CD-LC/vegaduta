package com.agenticai.core.tenancy.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * {@link ScheduledAnnotationBeanPostProcessor} that leaves the {@code @Scheduled} methods of the
 * beans named by {@code app.scheduling.skip-bean-classes} unregistered.
 *
 * <p>Everything else is inherited untouched, so a bean that is not named is scheduled by exactly
 * the code path Spring ships. Installed only when the skip list is non-empty (see {@link
 * SchedulingRoleRegistrar}).
 */
public class RoleFilteringScheduledAnnotationBeanPostProcessor
        extends ScheduledAnnotationBeanPostProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(RoleFilteringScheduledAnnotationBeanPostProcessor.class);

    private final SchedulingRoleMatcher skip;

    public RoleFilteringScheduledAnnotationBeanPostProcessor(SchedulingRoleMatcher skip) {
        this.skip = skip;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> target = AopProxyUtils.ultimateTargetClass(bean);
        if (skip.matches(target)) {
            // A broad pattern (a pool that skips com.agenticai.core.*) matches nearly every bean;
            // only the ones
            // that actually have @Scheduled methods are worth a line.
            if (hasScheduledMethods(target)) {
                log.info(
                        "Scheduling role: NOT scheduling @Scheduled methods of {} (bean '{}') -"
                                + " listed in app.scheduling.skip-bean-classes, another deployment"
                                + " shape owns it",
                        target.getName(),
                        beanName);
            }
            return bean;
        }
        return super.postProcessAfterInitialization(bean, beanName);
    }

    /**
     * Whether the class declares at least one {@code @Scheduled} (or {@code @Schedules}) method.
     */
    static boolean hasScheduledMethods(Class<?> type) {
        return !MethodIntrospector.selectMethods(
                        type,
                        (MethodIntrospector.MetadataLookup<Boolean>)
                                method ->
                                        AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)
                                                        || AnnotatedElementUtils.hasAnnotation(
                                                                method, Schedules.class)
                                                ? Boolean.TRUE
                                                : null)
                .isEmpty();
    }
}
