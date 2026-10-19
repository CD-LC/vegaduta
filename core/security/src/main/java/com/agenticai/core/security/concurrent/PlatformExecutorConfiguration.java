package com.agenticai.core.security.concurrent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one shared executor for blocking fire-and-forget / fan-out work that
 * used to land on {@code ForkJoinPool.commonPool()} (JAVA-008). Inject it
 * with {@code @Qualifier(PlatformExecutorConfiguration.PLATFORM_EXECUTOR)
 * Executor}. Spring calls {@link ContextPropagatingExecutor#close()} on
 * shutdown (JAVA-026).
 */
@Configuration(proxyBeanMethods = false)
public class PlatformExecutorConfiguration {

    /** Bean name / qualifier of the shared executor. */
    public static final String PLATFORM_EXECUTOR = "platformVirtualExecutor";

    @Bean(name = PLATFORM_EXECUTOR, destroyMethod = "close")
    public ContextPropagatingExecutor platformVirtualExecutor(
        @Value("${app.executor.platform.max-concurrency:512}") int maxConcurrency
    ) {
        return new ContextPropagatingExecutor("platform-vt-", maxConcurrency);
    }
}
