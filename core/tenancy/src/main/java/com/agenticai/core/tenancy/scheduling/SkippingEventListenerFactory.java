package com.agenticai.core.tenancy.scheduling;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.EventListenerFactory;
import org.springframework.core.Ordered;

/**
 * Turns the {@code @EventListener} methods of the classes named by {@code
 * app.scheduling.skip-listener-classes} into no-ops, without editing them.
 *
 * <p>Spring's {@code EventListenerMethodProcessor} asks each registered {@link
 * EventListenerFactory}, in order, whether it supports a method and lets the
 * first taker build the listener. This one runs first and takes only methods
 * declared on a named class, handing back a listener that ignores every event;
 * everything else falls through to the default factories unchanged.
 *
 * <p>The match is per class, so only name a class whose EVERY listener belongs
 * to the other deployment shape (docs/deploy/DEPLOYMENT-SHAPES.md lists the
 * ones verified safe).
 */
public class SkippingEventListenerFactory implements EventListenerFactory, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SkippingEventListenerFactory.class);

    private final SchedulingRoleMatcher skip;

    public SkippingEventListenerFactory(SchedulingRoleMatcher skip) {
        this.skip = skip;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supportsMethod(Method method) {
        return skip.matches(method.getDeclaringClass());
    }

    @Override
    public ApplicationListener<?> createApplicationListener(String beanName, Class<?> type, Method method) {
        log.info("Scheduling role: NOT registering @EventListener {}#{} (bean '{}') - listed in "
            + "app.scheduling.skip-listener-classes, another deployment shape owns it",
            method.getDeclaringClass().getName(), method.getName(), beanName);
        return new IgnoringListener();
    }

    /** Receives every event and does nothing. */
    static final class IgnoringListener implements ApplicationListener<ApplicationEvent> {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            // intentionally empty - see class javadoc
        }
    }
}
