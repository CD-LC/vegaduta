package com.agenticai.core.startup;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Switches on Spring's own start-up recorder ({@link BufferingApplicationStartup})
 * when {@code app.startup.profiling.enabled=true}, so cold-start work can be
 * profiled from data (per-bean instantiate time, component scan, post
 * processors) instead of guessed from log timestamps. Default OFF: with the flag
 * unset this class does nothing and the context keeps Spring's no-op
 * {@code ApplicationStartup.DEFAULT}, i.e. zero overhead and identical
 * behaviour.
 *
 * <p>Registered in {@code META-INF/spring.factories} as an
 * {@link ApplicationContextInitializer} rather than set in {@code main()} so it
 * is entry-point agnostic (API server, app-worker, app-jobs, tests) and can read
 * the flag from the fully loaded {@link Environment} (application.yml plus env
 * vars) - an initializer runs after config data is loaded and before the context
 * refreshes, which is exactly when {@code setApplicationStartup} still takes
 * effect for the whole refresh.
 *
 * <p>What it feeds: {@link StartupProfilingReporter} logs the slowest steps and
 * can write the whole timeline to a file at readiness, and Spring Boot's
 * {@code startup} actuator endpoint appears (only if an operator also exposes
 * it, see application.yml) because it requires a {@link BufferingApplicationStartup}
 * bean, which the context registers from this instance.
 *
 * <p>Fail-safe: any problem reading the flag is swallowed (logged once) and
 * start-up proceeds un-profiled.
 */
public class StartupProfilingInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String ENABLED_PROPERTY = "app.startup.profiling.enabled";
    static final String CAPACITY_PROPERTY = "app.startup.profiling.capacity";

    /**
     * About 1045 stereotype classes, 200+ repositories and the config-class parsing
     * steps record on the order of 10-15k steps; 50k leaves headroom for the
     * per-bean post-processing steps without the buffer filling (a full buffer
     * silently drops later steps, which the reporter flags).
     */
    static final int DEFAULT_CAPACITY = 50_000;
    static final int MIN_CAPACITY = 1_000;

    private static final Log log = LogFactory.getLog(StartupProfilingInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        try {
            Environment environment = context.getEnvironment();
            if (!Boolean.TRUE.equals(environment.getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.FALSE))) {
                return;
            }
            int capacity = environment.getProperty(CAPACITY_PROPERTY, Integer.class, DEFAULT_CAPACITY);
            context.setApplicationStartup(new BufferingApplicationStartup(Math.max(MIN_CAPACITY, capacity)));
            log.info("Start-up profiling ON (" + ENABLED_PROPERTY + "=true): recording Spring start-up steps, capacity "
                + Math.max(MIN_CAPACITY, capacity));
        } catch (RuntimeException e) {
            log.warn("Could not enable start-up profiling - continuing without it: " + e);
        }
    }
}
