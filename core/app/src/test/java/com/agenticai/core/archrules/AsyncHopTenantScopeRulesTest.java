package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.archrules.fixtures.AsyncHopFixtures;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tenant context does not survive a thread hop. Tenants are Postgres schemas picked per request
 * from {@code TenantContext} (a ThreadLocal), so any work handed to another thread - an executor,
 * an async {@code CompletableFuture} stage, a Reactor scheduler, a raw {@code Thread}, an
 * {@code @Async} method - runs against the wrong schema, no schema (fails closed, but silently
 * drops the work) or, on a pooled thread that was left holding a tenant, ANOTHER tenant's schema,
 * unless the tenant is captured as data on the originating thread and re-applied on the worker.
 *
 * <p>The approved ways to do that are {@code TenantScope} (capture on the originating thread,
 * {@code run}/{@code get}/{@code wrap*} on the worker, always restores the thread), anything in
 * {@code com.agenticai.core.security.concurrent} ({@code TenantAwareExecutors}, {@code
 * TenantAwareSchedulers}, {@code ContextPropagatingExecutor}; ADR-0024), and {@code
 * TenantScopedFlux} for a Reactor subscription. {@code RawExecutorBanRulesTest} makes every
 * executor in core come from that factory; this rule still checks each hand-off, because a field
 * typed {@code ExecutorService} does not say where its executor came from.
 *
 * <p>This guard fails when a class hands work to another thread and does not go through one of
 * them: for every method (a lambda counts as part of the method that contains it) that makes a
 * flagged call - see {@link #flaggedCall} - the method must also call the approved wrapper, or its
 * top-level class must be listed in {@code archrules/async-hop-allowlist.txt} with a reason. An
 * allowlist entry says "this hop was reviewed and is safe": it carries the tenant by hand (explicit
 * tenant id as data, a set/restore pair), touches only control-plane or non-tenant state, or is
 * deliberately tenant-less. A new async hop in a new class therefore fails here until someone
 * either routes it through the wrapper or writes down why it does not need one.
 *
 * <p>Honest limits: this is a call-site rule, not a data-flow proof. It cannot tell that the
 * wrapper in a method wraps the particular hop next to it (a method that wraps one hop and forgets
 * another passes), it does not see a hop made through a library the project does not call directly
 * (a Spring {@code TaskExecutor} bean's {@code @Async} proxy is covered only by the annotation
 * check), and Reactor callbacks that merely run on whatever thread delivers a signal ({@code
 * doOnComplete}, {@code subscribe(..)} lambdas) are not hops at the call site at all - those remain
 * a review item. An allowlisted class is exempt as a whole, so a second hop added to it later is
 * not noticed; entries should stay small and specific.
 *
 * <p>The allowlist must only shrink or grow with a reason: an entry whose class no longer makes an
 * unwrapped flagged call fails the test too, so it cannot rot.
 */
class AsyncHopTenantScopeRulesTest {

    private static final String ROOT = "com.agenticai.core";
    private static final String ALLOWLIST = "/archrules/async-hop-allowlist.txt";

    private static final Set<String> APPROVED_WRAPPER_OWNERS =
            Set.of(
                    "com.agenticai.core.security.TenantScope",
                    "com.agenticai.core.security.concurrent.ContextPropagatingExecutor",
                    "com.agenticai.core.agents.turn.TenantScopedFlux");

    /**
     * ADR-0024: every type in the factory package captures the tenant on submit (the executors and
     * schedulers {@code TenantAwareExecutors} / {@code TenantAwareSchedulers} build), so a call to
     * it, or a hand-off to one of its concrete types, is a wrapped hop.
     */
    private static final String APPROVED_WRAPPER_PACKAGE =
            "com.agenticai.core.security.concurrent.";

    static boolean isApprovedWrapper(String ownerName) {
        return APPROVED_WRAPPER_OWNERS.contains(ownerName)
                || ownerName.startsWith(APPROVED_WRAPPER_PACKAGE);
    }

    private static final Set<String> EXECUTOR_SUBMIT_METHODS =
            Set.of(
                    "execute",
                    "submit",
                    "schedule",
                    "scheduleAtFixedRate",
                    "scheduleWithFixedDelay",
                    "invokeAll",
                    "invokeAny");

    private static final Set<String> REACTOR_HOP_OPERATORS =
            Set.of(
                    "subscribeOn",
                    "publishOn",
                    "interval",
                    "delay",
                    "delayElements",
                    "delaySubscription",
                    // A timeout that fires emits TimeoutException on Reactor's raw parallel
                    // timer thread, so every onErrorResume/doOnError after it runs with no
                    // TenantContext (fails closed, but drops the fallback or the partial
                    // transcript). The timer-thread continuation is a hop like delay().
                    "timeout");

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

    /**
     * What a flagged call is, or null when the call is not an async hop. Creating a pool or a
     * scheduler is not itself a hop - handing it work is - so only the hand-off is flagged.
     */
    static String flaggedCall(JavaCall<?> call) {
        JavaClass owner = call.getTargetOwner();
        String ownerName = owner.getName();
        String method = call.getName();
        if (method.equals("<init>")) {
            return null;
        }
        if (ownerName.equals("java.util.concurrent.CompletableFuture")
                && (method.equals("supplyAsync")
                        || method.equals("runAsync")
                        || method.endsWith("Async"))) {
            return "CompletableFuture." + method;
        }
        if (method.equals("start") && isAssignable(owner, "java.lang.Thread")) {
            return "Thread.start";
        }
        if (ownerName.equals("java.lang.Thread")
                && (method.equals("ofVirtual")
                        || method.equals("ofPlatform")
                        || method.equals("startVirtualThread"))) {
            return "Thread." + method;
        }
        if (EXECUTOR_SUBMIT_METHODS.contains(method)
                && !isApprovedWrapper(ownerName)
                && (isAssignable(owner, "java.util.concurrent.Executor")
                        || isAssignable(owner, "reactor.core.scheduler.Scheduler"))) {
            return owner.getSimpleName() + "." + method;
        }
        if (REACTOR_HOP_OPERATORS.contains(method)
                && (isAssignable(owner, "reactor.core.publisher.Flux")
                        || isAssignable(owner, "reactor.core.publisher.Mono"))) {
            return owner.getSimpleName() + "." + method;
        }
        return null;
    }

    private static boolean isAssignable(JavaClass type, String superTypeName) {
        return type.getName().equals(superTypeName) || type.isAssignableTo(superTypeName);
    }

    private static final Pattern LAMBDA_NAME = Pattern.compile("^lambda\\$(.+?)\\$\\d+(\\$\\d+)*$");

    /** The method a (possibly lambda-synthetic) code unit belongs to. */
    static String methodGroup(JavaCodeUnit unit) {
        Matcher m = LAMBDA_NAME.matcher(unit.getName());
        String base = m.matches() ? m.group(1) : unit.getName();
        // Nested lambdas nest their enclosing method name: lambda$outer$0$1 -> outer.
        return base;
    }

    static String topLevelName(JavaClass c) {
        String name = c.getName();
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    /** One scan result: where unwrapped hops are, by top-level class. */
    record Scan(Map<String, List<String>> unwrappedSites, int hopsSeen, int wrappedHops) {}

    static Scan scan(JavaClasses imported, String packagePrefix) {
        Map<String, List<String>> unwrapped = new TreeMap<>();
        int hops = 0;
        int wrapped = 0;
        for (JavaClass c : imported) {
            if (!c.getPackageName().startsWith(packagePrefix)
                    || (c.getPackageName() + ".").equals(APPROVED_WRAPPER_PACKAGE)) {
                // The factory package IS the wrapper: its hand-offs propagate by construction.
                continue;
            }
            String top = topLevelName(c);
            // group -> flagged sites, and whether the group calls the approved wrapper
            Map<String, List<String>> flaggedByGroup = new LinkedHashMap<>();
            Set<String> groupsWithWrapper = new HashSet<>();
            for (JavaCodeUnit unit : c.getCodeUnits()) {
                String group = c.getName() + "#" + methodGroup(unit);
                List<JavaCall<?>> calls = new ArrayList<>();
                calls.addAll(unit.getMethodCallsFromSelf());
                calls.addAll(unit.getConstructorCallsFromSelf());
                for (JavaCall<?> call : calls) {
                    if (isApprovedWrapper(call.getTargetOwner().getName())) {
                        groupsWithWrapper.add(group);
                    }
                    String flagged = flaggedCall(call);
                    if (flagged != null) {
                        flaggedByGroup
                                .computeIfAbsent(group, g -> new ArrayList<>())
                                .add(flagged + " (line " + call.getLineNumber() + ")");
                    }
                }
                if (unit.isAnnotatedWith("org.springframework.scheduling.annotation.Async")
                        || c.isAnnotatedWith("org.springframework.scheduling.annotation.Async")) {
                    flaggedByGroup
                            .computeIfAbsent(group, g -> new ArrayList<>())
                            .add("@Async method");
                }
            }
            for (Map.Entry<String, List<String>> e : flaggedByGroup.entrySet()) {
                hops += e.getValue().size();
                if (groupsWithWrapper.contains(e.getKey())) {
                    wrapped += e.getValue().size();
                    continue;
                }
                for (String site : e.getValue()) {
                    unwrapped
                            .computeIfAbsent(top, t -> new ArrayList<>())
                            .add(e.getKey().substring(e.getKey().indexOf('#') + 1) + ": " + site);
                }
            }
        }
        return new Scan(unwrapped, hops, wrapped);
    }

    /** Parsed {@code FQCN | reason} lines; blank lines and {@code #} comments ignored. */
    static Map<String, String> loadAllowlist() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = AsyncHopTenantScopeRulesTest.class.getResourceAsStream(ALLOWLIST)) {
            assertThat(in).as("test resource " + ALLOWLIST).isNotNull();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> problems = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int bar = trimmed.indexOf('|');
                String name = bar < 0 ? trimmed : trimmed.substring(0, bar).strip();
                String reason = bar < 0 ? "" : trimmed.substring(bar + 1).strip();
                if (reason.length() < 15) {
                    problems.add(name + " has no real reason (write why this hop is tenant-safe)");
                }
                if (entries.put(name, reason) != null) {
                    problems.add(name + " is listed twice");
                }
            }
            assertThat(problems).as("allowlist format problems in " + ALLOWLIST).isEmpty();
        }
        return entries;
    }

    @Test
    void everyAsyncHopCarriesTheTenantOrIsReviewedOnTheAllowlist() throws Exception {
        Scan scan = scan(classes, ROOT + ".");
        Map<String, String> allowlist = loadAllowlist();

        assertThat(scan.hopsSeen())
                .as("the scan must actually see the project's async hops, or this guard is vacuous")
                .isGreaterThan(100);
        assertThat(scan.wrappedHops())
                .as("the scan must recognise the approved wrappers, or every hop looks unwrapped")
                .isGreaterThan(5);

        Set<String> unlisted = new TreeSet<>(scan.unwrappedSites().keySet());
        unlisted.removeAll(allowlist.keySet());
        StringBuilder message = new StringBuilder();
        for (String cls : unlisted) {
            message.append("\n  ").append(cls);
            for (String site : scan.unwrappedSites().get(cls)) {
                message.append("\n      ").append(site);
            }
        }
        Set<String> stale = new TreeSet<>(allowlist.keySet());
        stale.removeAll(scan.unwrappedSites().keySet());
        SoftAssertions.assertSoftly(
                soft -> {
                    soft.assertThat(unlisted)
                            .as(
                                    "These classes hand work to another thread without TenantScope"
                                            + " / ContextPropagatingExecutor / TenantScopedFlux in the"
                                            + " same method. TenantContext is a ThreadLocal: capture it"
                                            + " on the originating thread (TenantScope.capture()) and"
                                            + " re-apply it on the worker (scope.run(..),"
                                            + " scope.wrapRunnable(..),"
                                            + " ContextPropagatingExecutor.propagating(..)), never"
                                            + " leave it set on a pooled thread. If the hop genuinely"
                                            + " needs no tenant (control-plane/non-tenant work, tenant"
                                            + " carried as an explicit id, set/restore done by hand),"
                                            + " add 'fully.qualified.Class | reason' to "
                                            + "core/app/src/test/resources"
                                            + ALLOWLIST
                                            + ". Offenders:"
                                            + message)
                            .isEmpty();
                    soft.assertThat(stale)
                            .as(
                                    "These allowlist entries no longer make an unwrapped async hop"
                                            + " (the class was fixed, renamed or removed): delete"
                                            + " them from "
                                            + ALLOWLIST)
                            .isEmpty();
                });
    }

    /**
     * The rule must really flag what it claims to, or a refactor of the matchers could silently
     * blind it.
     */
    @Test
    void theRuleRecognisesEachKindOfHopOnAKnownFixture() {
        JavaClasses fixture = new ClassFileImporter().importClasses(AsyncHopFixtures.class);

        Scan scan = scan(fixture, "com.agenticai.core.archrules.fixtures");

        List<String> sites = scan.unwrappedSites().get(AsyncHopFixtures.class.getName());
        assertThat(sites).isNotNull();
        assertThat(sites)
                .anySatisfy(
                        site ->
                                assertThat(site)
                                        .startsWith("supplyAsync: CompletableFuture.supplyAsync"))
                .anySatisfy(
                        site -> assertThat(site).startsWith("runAsync: CompletableFuture.runAsync"))
                .anySatisfy(
                        site ->
                                assertThat(site)
                                        .startsWith(
                                                "thenApplyAsync: CompletableFuture.thenApplyAsync"))
                .anySatisfy(site -> assertThat(site).startsWith("rawThread: Thread.start"))
                .anySatisfy(site -> assertThat(site).startsWith("virtualThread: Thread.ofVirtual"))
                .anySatisfy(
                        site ->
                                assertThat(site)
                                        .startsWith("executorSubmit: ExecutorService.submit"))
                .anySatisfy(
                        site -> assertThat(site).startsWith("executorExecute: Executor.execute"))
                .anySatisfy(site -> assertThat(site).startsWith("reactorHops: Mono.subscribeOn"))
                .anySatisfy(site -> assertThat(site).startsWith("reactorHops: Mono.publishOn"))
                .anySatisfy(site -> assertThat(site).startsWith("reactorTimeout: Mono.timeout"));
        assertThat(sites)
                .as("creating a pool is not a hop")
                .noneMatch(site -> site.startsWith("newPool"));
        assertThat(sites)
                .as("a hop whose method (or lambda inside it) uses TenantScope is wrapped")
                .noneMatch(site -> site.startsWith("wrappedHop"));
        assertThat(scan.wrappedHops()).isEqualTo(2);
    }
}
