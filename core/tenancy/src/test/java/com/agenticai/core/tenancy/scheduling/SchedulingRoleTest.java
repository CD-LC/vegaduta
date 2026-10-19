package com.agenticai.core.tenancy.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * The deployment-shape gate: pattern matching, and the real Spring wiring (the
 * {@code @EnableScheduling} post-processor swap and the listener factory) exercised in a throwaway
 * context.
 */
class SchedulingRoleTest {

    // ------------------------------------------------------------------ matcher

    @Test
    void anEmptyMatcherMatchesNothing() {
        SchedulingRoleMatcher matcher = SchedulingRoleMatcher.of(List.of());
        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matches("com.agenticai.core.agents.job.AgentJobRunner"));
        assertFalse(SchedulingRoleMatcher.of(null).matches(String.class));
    }

    @Test
    void exactNamesMatchOnlyThatClass() {
        SchedulingRoleMatcher matcher =
                SchedulingRoleMatcher.of(List.of("com.agenticai.core.agents.job.AgentJobRunner"));
        assertTrue(matcher.matches("com.agenticai.core.agents.job.AgentJobRunner"));
        assertFalse(matcher.matches("com.agenticai.core.agents.job.AgentJobRunnerHelper"));
        assertFalse(matcher.matches("com.agenticai.core.agents.job.AgentJobExecutor"));
    }

    @Test
    void aTrailingStarIsAPackagePrefix() {
        SchedulingRoleMatcher matcher =
                SchedulingRoleMatcher.of(List.of("com.agenticai.core.omnichannel.*"));
        assertTrue(matcher.matches("com.agenticai.core.omnichannel.email.EmailInboundPollingJob"));
        assertFalse(matcher.matches("com.agenticai.core.workflow.WorkflowScheduler"));
    }

    @Test
    void commaSeparatedEntriesAreSplitTrimmedAndBlanksIgnored() {
        SchedulingRoleMatcher matcher = SchedulingRoleMatcher.of(List.of(" a.B , ,c.D*,", "e.F"));
        assertTrue(matcher.matches("a.B"));
        assertTrue(matcher.matches("c.DTail"));
        assertTrue(matcher.matches("e.F"));
        assertFalse(matcher.matches("a.C"));
    }

    @Test
    void aCglibProxyNameMatchesItsTarget() {
        SchedulingRoleMatcher matcher = SchedulingRoleMatcher.of(List.of("x.Foo"));
        assertTrue(matcher.matches("x.Foo$$SpringCGLIB$$0"));
    }

    @Test
    void aKeepListCarvesExceptionsOutOfABroadSkipPattern() {
        SchedulingRoleMatcher skipAllButKnowledge =
                SchedulingRoleMatcher.of(List.of("com.agenticai.core.*"))
                        .except(
                                SchedulingRoleMatcher.of(
                                        List.of(
                                                "com.agenticai.core.knowledge.KnowledgeIngestionJobRunner",
                                                "com.agenticai.core.knowledge.KnowledgeReembed*")));
        assertTrue(skipAllButKnowledge.matches("com.agenticai.core.agents.job.AgentJobRunner"));
        assertFalse(
                skipAllButKnowledge.matches(
                        "com.agenticai.core.knowledge.KnowledgeIngestionJobRunner"));
        assertFalse(
                skipAllButKnowledge.matches(
                        "com.agenticai.core.knowledge.KnowledgeReembedJobRunner"));
        assertFalse(
                skipAllButKnowledge.matches(
                        "com.agenticai.core.knowledge.KnowledgeIngestionJobRunner$$SpringCGLIB$$0"));
        assertTrue(
                skipAllButKnowledge.matches(
                        "com.agenticai.core.knowledge.VectorSchemaInitializer"));
        assertFalse(skipAllButKnowledge.matches("org.springframework.Anything"));
    }

    @Test
    void anEmptyKeepListLeavesTheMatcherExactlyAsItWas() {
        SchedulingRoleMatcher skip = SchedulingRoleMatcher.of(List.of("a.B"));
        assertEquals(skip, skip.except(SchedulingRoleMatcher.of(List.of())));
        assertEquals(skip, skip.except(null));
        assertTrue(
                SchedulingRoleMatcher.of(List.of())
                        .except(SchedulingRoleMatcher.of(List.of("a.B")))
                        .isEmpty(),
                "a keep list without a skip list must stay a no-op, not start skipping things");
    }

    @Test
    void onlyBeansWithScheduledMethodsAreWorthALogLine() {
        assertTrue(
                RoleFilteringScheduledAnnotationBeanPostProcessor.hasScheduledMethods(
                        SkippedJob.class));
        assertFalse(
                RoleFilteringScheduledAnnotationBeanPostProcessor.hasScheduledMethods(
                        String.class));
        assertFalse(
                RoleFilteringScheduledAnnotationBeanPostProcessor.hasScheduledMethods(
                        Config.class));
    }

    // ------------------------------------------------------ real Spring wiring

    @Test
    void withNoPropertyEveryScheduledBeanIsRegisteredAsToday() {
        try (AnnotationConfigApplicationContext ctx = context(null, null)) {
            ScheduledAnnotationBeanPostProcessor processor =
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertEquals(
                    ScheduledAnnotationBeanPostProcessor.class,
                    processor.getClass(),
                    "no skip list must leave Spring's own post-processor in place");
            assertEquals(3, processor.getScheduledTasks().size());
        }
    }

    @Test
    void aListedBeanIsNotScheduledAndAnUnlistedOneStillIs() {
        try (AnnotationConfigApplicationContext ctx = context(SkippedJob.class.getName(), null)) {
            ScheduledAnnotationBeanPostProcessor processor =
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertEquals(
                    RoleFilteringScheduledAnnotationBeanPostProcessor.class, processor.getClass());
            assertEquals(2, processor.getScheduledTasks().size());
        }
    }

    @Test
    void aListedClassKeepsItsStartupListenerSilentAndOthersStillFire() {
        SkippedJob.refreshes.set(0);
        KeptJob.refreshes.set(0);
        try (AnnotationConfigApplicationContext ctx = context(null, SkippedJob.class.getName())) {
            ctx.publishEvent(new ContextRefreshedEvent(ctx));
        }
        assertEquals(0, SkippedJob.refreshes.get());
        assertTrue(KeptJob.refreshes.get() > 0);
    }

    @Test
    void aBroadSkipWithAKeepListSchedulesOnlyTheKeptBean() {
        try (AnnotationConfigApplicationContext ctx =
                context(SchedulingRoleTest.class.getName() + "$*", null, KeptJob.class.getName())) {
            ScheduledAnnotationBeanPostProcessor processor =
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertEquals(
                    RoleFilteringScheduledAnnotationBeanPostProcessor.class, processor.getClass());
            assertEquals(
                    1,
                    processor.getScheduledTasks().size(),
                    "SkippedJob is skipped by the broad pattern, KeptJob is the named exception");
        }
    }

    // The pool wiring end to end: the real environment (OS env var names, relaxed binding) through
    // SchedulingRoleRegistrar into the real post-processor.

    private static final String POOL_DEFS = "app.scheduling.pools.p[0]=" + KeptJob.class.getName();

    @Test
    void aPoolPodBoundFromTheEnvVarSchedulesItsPoolAndItsLifecycleClassesOnly() {
        try (AnnotationConfigApplicationContext ctx =
                poolContext(
                        "APP_SCHEDULING_POOL=p",
                        POOL_DEFS,
                        "app.scheduling.pool-lifecycle.p[0]=" + LifecycleJob.class.getName())) {
            assertEquals(
                    2,
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks()
                            .size(),
                    "the pool's KeptJob and its lifecycle LifecycleJob run; SkippedJob does not");
        }
    }

    @Test
    void aPoolWithoutLifecycleEntriesSchedulesOnlyItsOwnClasses() {
        try (AnnotationConfigApplicationContext ctx =
                poolContext("APP_SCHEDULING_POOL=p", POOL_DEFS)) {
            assertEquals(
                    1,
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks()
                            .size());
        }
    }

    @Test
    void theMainPodBoundFromTheEnvVarSkipsOffloadedPoolClassesOnly() {
        try (AnnotationConfigApplicationContext ctx =
                poolContext("APP_SCHEDULING_OFFLOADED_POOLS=p", POOL_DEFS)) {
            assertEquals(
                    2,
                    ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks()
                            .size(),
                    "KeptJob moved to the pool; SkippedJob and LifecycleJob stay on the main pod");
        }
    }

    private static AnnotationConfigApplicationContext poolContext(String... settings) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        for (String setting : settings) {
            if (setting.startsWith("APP_")) {
                TestPropertyValues.of(setting)
                        .applyTo(ctx.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT);
            } else {
                TestPropertyValues.of(setting).applyTo(ctx);
            }
        }
        ctx.register(Config.class, SchedulingRoleRegistrar.class);
        ctx.refresh();
        return ctx;
    }

    private static AnnotationConfigApplicationContext context(
            String skipBeans, String skipListeners) {
        return context(skipBeans, skipListeners, null);
    }

    private static AnnotationConfigApplicationContext context(
            String skipBeans, String skipListeners, String keepBeans) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        if (skipBeans != null) {
            TestPropertyValues.of("app.scheduling.skip-bean-classes=" + skipBeans).applyTo(ctx);
        }
        if (keepBeans != null) {
            TestPropertyValues.of("app.scheduling.keep-bean-classes=" + keepBeans).applyTo(ctx);
        }
        if (skipListeners != null) {
            TestPropertyValues.of("app.scheduling.skip-listener-classes=" + skipListeners)
                    .applyTo(ctx);
        }
        // Registered as a BEAN (not addBeanFactoryPostProcessor) so it runs in the
        // same slot as the component-scanned production one: after the
        // configuration-class processor has registered @EnableScheduling's bean.
        ctx.register(Config.class, SchedulingRoleRegistrar.class);
        ctx.refresh();
        return ctx;
    }

    @Configuration
    @EnableScheduling
    static class Config {
        @Bean
        SkippedJob skippedJob() {
            return new SkippedJob();
        }

        @Bean
        KeptJob keptJob() {
            return new KeptJob();
        }

        @Bean
        LifecycleJob lifecycleJob() {
            return new LifecycleJob();
        }
    }

    static class SkippedJob {
        static final AtomicInteger refreshes = new AtomicInteger();

        // Delays far beyond any test run: registration is what is asserted, not firing.
        @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
        void tick() {}

        @EventListener(ContextRefreshedEvent.class)
        void onRefresh() {
            refreshes.incrementAndGet();
        }
    }

    static class KeptJob {
        static final AtomicInteger refreshes = new AtomicInteger();

        @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
        void tick() {}

        @EventListener(ContextRefreshedEvent.class)
        void onRefresh() {
            refreshes.incrementAndGet();
        }
    }

    static class LifecycleJob {
        @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
        void tick() {}
    }
}
