package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.archrules.fixtures.RawExecutorFixtures;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR-0024: core creates threads, pools and schedulers in ONE place, {@code
 * com.agenticai.core.security.concurrent.TenantAwareExecutors} (and {@code TenantAwareSchedulers}
 * for Reactor), whose executors capture the tenant on submit and re-apply it on the worker. This
 * test fails on any raw form outside that package:
 *
 * <ul>
 *   <li>any {@code java.util.concurrent.Executors} factory method;
 *   <li>{@code new Thread(..)} (or a {@code Thread} subclass), {@code Thread.ofVirtual()}, {@code
 *       Thread.ofPlatform()}, {@code Thread.startVirtualThread(..)};
 *   <li>constructing a {@code ThreadPoolExecutor}/{@code ScheduledThreadPoolExecutor}/{@code
 *       ForkJoinPool} (or a subclass), or {@code ForkJoinPool.commonPool()};
 *   <li>constructing Spring's {@code ThreadPoolTaskExecutor}, {@code ThreadPoolTaskScheduler},
 *       {@code SimpleAsyncTaskExecutor}, {@code SimpleAsyncTaskScheduler}, {@code
 *       VirtualThreadTaskExecutor};
 *   <li>a {@code CompletableFuture.*Async} call without an {@code Executor} argument (it runs on
 *       the JVM-wide common pool, with no tenant);
 *   <li>any {@code reactor.core.scheduler.Schedulers} method.
 * </ul>
 *
 * <p>Nothing is frozen. A site that truly cannot go through the factory is listed in {@link
 * #ALLOWED} with the reason; an entry that no longer has a raw form fails the test, so the list
 * only shrinks.
 *
 * <p>This rule is about where executors COME FROM. {@code AsyncHopTenantScopeRulesTest} is the
 * companion rule about handing work to an executor that did not come from here.
 */
class RawExecutorBanRulesTest {

    private static final String ROOT = "com.agenticai.core";
    static final String FACTORY_PACKAGE = "com.agenticai.core.security.concurrent";

    /** Top-level class -> why it may create threads or pools by hand. Keep this tiny. */
    static final Map<String, String> ALLOWED = Map.of();

    private static final Set<String> BANNED_CONSTRUCTED_TYPES =
            Set.of(
                    "java.lang.Thread",
                    "java.util.concurrent.ThreadPoolExecutor",
                    "java.util.concurrent.ForkJoinPool",
                    "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor",
                    "org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler",
                    "org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler",
                    "org.springframework.core.task.SimpleAsyncTaskExecutor",
                    "org.springframework.core.task.VirtualThreadTaskExecutor");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ROOT);
    }

    /**
     * Releases the imported class graphs. Surefire runs all of this module's tests in one JVM, so
     * the statics of a finished test class would otherwise stay on the heap for the rest of the
     * run, and the other ArchUnit tests import the classpath again.
     */
    @AfterAll
    static void releaseImports() {
        classes = null;
    }

    /** What raw form a call is, or null when it is allowed. */
    static String rawForm(JavaCall<?> call) {
        JavaClass owner = call.getTargetOwner();
        String ownerName = owner.getName();
        String method = call.getName();
        if (method.equals("<init>")) {
            for (String banned : BANNED_CONSTRUCTED_TYPES) {
                if (ownerName.equals(banned) || owner.isAssignableTo(banned)) {
                    return "new " + owner.getSimpleName();
                }
            }
            return null;
        }
        if (ownerName.equals("java.util.concurrent.Executors")) {
            return "Executors." + method;
        }
        if (ownerName.equals("java.lang.Thread")
                && (method.equals("ofVirtual")
                        || method.equals("ofPlatform")
                        || method.equals("startVirtualThread"))) {
            return "Thread." + method;
        }
        if (ownerName.equals("java.util.concurrent.ForkJoinPool") && method.equals("commonPool")) {
            return "ForkJoinPool.commonPool";
        }
        if (ownerName.equals("java.util.concurrent.CompletableFuture")
                && method.endsWith("Async")
                && call.getTarget().getRawParameterTypes().stream()
                        .noneMatch(t -> t.isAssignableTo("java.util.concurrent.Executor"))) {
            return "CompletableFuture." + method + " without an executor";
        }
        if (ownerName.equals("reactor.core.scheduler.Schedulers")) {
            return "Schedulers." + method;
        }
        return null;
    }

    static String topLevelName(JavaClass c) {
        String name = c.getName();
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    /** Top-level class -> raw-form sites, for classes under {@code packagePrefix}. */
    static Map<String, List<String>> scan(JavaClasses imported, String packagePrefix) {
        Map<String, List<String>> sites = new TreeMap<>();
        for (JavaClass c : imported) {
            if (!c.getPackageName().startsWith(packagePrefix)
                    || c.getPackageName().equals(FACTORY_PACKAGE)) {
                continue;
            }
            for (JavaCodeUnit unit : c.getCodeUnits()) {
                List<JavaCall<?>> calls = new ArrayList<>();
                calls.addAll(unit.getMethodCallsFromSelf());
                calls.addAll(unit.getConstructorCallsFromSelf());
                for (JavaCall<?> call : calls) {
                    String raw = rawForm(call);
                    if (raw != null) {
                        sites.computeIfAbsent(topLevelName(c), k -> new ArrayList<>())
                                .add(
                                        AsyncHopTenantScopeRulesTest.methodGroup(unit)
                                                + ": "
                                                + raw
                                                + " (line "
                                                + call.getLineNumber()
                                                + ")");
                    }
                }
            }
        }
        return sites;
    }

    @Test
    void threadsPoolsAndSchedulersComeOnlyFromTheTenantAwareFactory() {
        Map<String, List<String>> sites = scan(classes, ROOT + ".");

        assertThat(classes.containPackage(FACTORY_PACKAGE))
                .as("the factory package must be on the scanned classpath")
                .isTrue();
        long factoryCalls =
                classes.stream()
                        .filter(c -> !c.getPackageName().equals(FACTORY_PACKAGE))
                        .flatMap(c -> c.getMethodCallsFromSelf().stream())
                        .filter(
                                call ->
                                        call.getTargetOwner()
                                                .getName()
                                                .startsWith(FACTORY_PACKAGE + ".TenantAware"))
                        .count();
        assertThat(factoryCalls)
                .as(
                        "the scan must see core's executors being built by the factory, or it is vacuous")
                .isGreaterThan(50);

        Set<String> offenders = new TreeSet<>(sites.keySet());
        offenders.removeAll(ALLOWED.keySet());
        StringBuilder message = new StringBuilder();
        for (String cls : offenders) {
            message.append("\n  ").append(cls);
            for (String site : sites.get(cls)) {
                message.append("\n      ").append(site);
            }
        }
        Set<String> stale = new TreeSet<>(ALLOWED.keySet());
        stale.removeAll(sites.keySet());
        SoftAssertions.assertSoftly(
                soft -> {
                    soft.assertThat(offenders)
                            .as(
                                    "These classes create threads, pools or schedulers by hand."
                                            + " Use com.agenticai.core.security.concurrent"
                                            + ".TenantAwareExecutors (or TenantAwareSchedulers for"
                                            + " Reactor), whose executors carry the submitting"
                                            + " thread's tenant to the worker (ADR-0024). Pass an"
                                            + " executor to CompletableFuture.*Async. Offenders:"
                                            + message)
                            .isEmpty();
                    soft.assertThat(stale)
                            .as(
                                    "These ALLOWED entries no longer create a raw executor: delete them")
                            .isEmpty();
                });
    }

    /** The rule must flag what it claims to, or a refactor of the matcher could blind it. */
    @Test
    void theRuleRecognisesEachRawFormOnAKnownFixture() {
        JavaClasses fixture = new ClassFileImporter().importClasses(RawExecutorFixtures.class);

        List<String> sites =
                scan(fixture, "com.agenticai.core.archrules.fixtures")
                        .get(RawExecutorFixtures.class.getName());

        assertThat(sites).isNotNull();
        assertThat(sites)
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "executorsFactory: Executors.newFixedThreadPool"))
                .anySatisfy(s -> assertThat(s).startsWith("rawThread: new Thread"))
                .anySatisfy(s -> assertThat(s).startsWith("virtualThread: Thread.ofVirtual"))
                .anySatisfy(s -> assertThat(s).startsWith("platformThread: Thread.ofPlatform"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "threadPoolConstructor: new ThreadPoolExecutor"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "scheduledPoolConstructor: new"
                                                        + " ScheduledThreadPoolExecutor"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith("forkJoinCommonPool: ForkJoinPool.commonPool"))
                .anySatisfy(s -> assertThat(s).startsWith("springPool: new ThreadPoolTaskExecutor"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "springSimpleAsync: new SimpleAsyncTaskExecutor"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "supplyAsyncWithoutExecutor:"
                                                        + " CompletableFuture.supplyAsync without"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "thenApplyAsyncWithoutExecutor:"
                                                        + " CompletableFuture.thenApplyAsync"))
                .anySatisfy(
                        s ->
                                assertThat(s)
                                        .startsWith(
                                                "reactorSchedulers: Schedulers.boundedElastic"));
        assertThat(sites)
                .as("an executor argument and the factory are allowed")
                .noneMatch(s -> s.startsWith("allowed"));
    }
}
