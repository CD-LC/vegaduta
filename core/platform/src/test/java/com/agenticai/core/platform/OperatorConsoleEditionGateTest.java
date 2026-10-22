package com.agenticai.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.platform.ops.MaintenanceModeFilter;
import com.agenticai.core.platform.ops.PlatformBroadcastService;
import com.agenticai.core.platform.spendcap.SpendCapAlertNotifier;
import com.agenticai.core.platform.spendcap.TenantSpendCapController;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

/**
 * Pins which platform beans the personal edition keeps. {@code PlatformController} (the LLM-pool
 * and tenant endpoints a personal owner with {@code ROLE_platform-admin} still drives), the usage
 * rollup, the broadcast and maintenance pair the public status page reads, and the spend-cap
 * controller and notifier are the keep list; every other platform bean is the operator console and
 * carries {@code @ConditionalOnEditionFeature(OPERATOR_CONSOLE)}.
 *
 * <p>The keep list is spelled out, so a bean added to {@code core.platform} later fails this test
 * until it is either gated or deliberately added here, and a bean on the keep list can never come
 * to inject a gated one (which would crash core at startup on personal: "No qualifying bean").
 * {@code EditionClosureRulesTest} in core/app proves the same from bytecode for the whole reactor;
 * this one fails inside the module.
 */
class OperatorConsoleEditionGateTest {

    private static final String PACKAGE = "com.agenticai.core.platform";

    /**
     * The beans that stay on personal: PlatformController's whole constructor closure plus the
     * rest.
     */
    private static final Set<String> KEPT =
            new TreeSet<>(
                    Set.of(
                            "PlatformController",
                            "PlatformLlmTaskService",
                            "TranslationJobSettingsService",
                            "TranslationRunService",
                            "PlatformTenantMetricsService",
                            "PlatformSpendService",
                            "PlatformTenantPlanService",
                            "PlatformObservabilityService",
                            "PlatformTenantDataResidencyService",
                            "LlmUsageRollupScheduler",
                            "PlatformBroadcastService",
                            "MaintenanceModeFilter",
                            "TenantSpendCapController",
                            "SpendCapAlertNotifier"));

    private static Set<Class<?>> components() throws ClassNotFoundException {
        // Conditions are evaluated by the scanner: switch on the profile and the two property-gated
        // beans so the
        // list is every operator-console class, whatever its other conditions.
        MockEnvironment env =
                new MockEnvironment()
                        .withProperty("app.landing-events.batch-enabled", "true")
                        .withProperty("abuse.alert.webhook.url", "https://example.invalid/hook");
        env.setActiveProfiles("security");
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, env);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<Class<?>> found = new HashSet<>();
        for (BeanDefinition d : scanner.findCandidateComponents(PACKAGE)) {
            Class<?> type = Class.forName(d.getBeanClassName());
            // Test fixtures live in the same package tree but are not production beans.
            if (type.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath()
                    .contains("test-classes")) {
                continue;
            }
            found.add(type);
        }
        return found;
    }

    private static boolean gated(Class<?> type) {
        ConditionalOnEditionFeature gate = type.getAnnotation(ConditionalOnEditionFeature.class);
        return gate != null && gate.value() == EditionFeature.OPERATOR_CONSOLE;
    }

    @Test
    void exactlyTheKeepListIsUngated() throws ClassNotFoundException {
        Set<String> ungated =
                components().stream()
                        .filter(c -> !gated(c))
                        .map(Class::getSimpleName)
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(ungated).isEqualTo(KEPT);
    }

    @Test
    void theOperatorConsoleIsAlmostEverythingElse() throws ClassNotFoundException {
        long gatedCount =
                components().stream().filter(OperatorConsoleEditionGateTest::gated).count();
        assertThat(gatedCount).isGreaterThanOrEqualTo(55);
    }

    @Test
    void noKeptBeanRequiresAGatedBean() throws ClassNotFoundException {
        Set<Class<?>> all = components();
        Set<String> broken = new TreeSet<>();
        for (Class<?> kept : all.stream().filter(c -> !gated(c)).toList()) {
            Deque<Class<?>> queue = new ArrayDeque<>();
            queue.add(kept);
            while (!queue.isEmpty()) {
                Class<?> c = queue.poll();
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (c.getDeclaredConstructors().length > 1
                            && !k.isAnnotationPresent(Autowired.class)) {
                        continue;
                    }
                    for (Class<?> p : k.getParameterTypes()) {
                        if (all.contains(p) && gated(p)) {
                            broken.add(kept.getSimpleName() + " -> " + p.getSimpleName());
                        }
                    }
                }
                Class<?> parent = c.getSuperclass();
                if (parent != null && parent.getName().startsWith(PACKAGE) && parent != c) {
                    queue.add(parent);
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    void theGateIsOffOnPersonalAndOnForEveryOtherEdition() {
        MockEnvironment personal =
                new MockEnvironment().withProperty("app.edition.tier", "personal");
        assertThat(EditionFeature.OPERATOR_CONSOLE.isEnabled(personal)).isFalse();
        for (String tier : new String[] {"lite", "full"}) {
            assertThat(
                            EditionFeature.OPERATOR_CONSOLE.isEnabled(
                                    new MockEnvironment().withProperty("app.edition.tier", tier)))
                    .isTrue();
        }
        assertThat(EditionFeature.OPERATOR_CONSOLE.isEnabled(new MockEnvironment())).isTrue();
    }

    /**
     * Component-scans {@code core.platform} the way core does (conditions evaluated, nothing
     * instantiated, no database) and returns the registered bean names.
     */
    private static Set<String> scannedBeanNames(MockEnvironment env) {
        env.setActiveProfiles("security");
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.setEnvironment(env);
            new ClassPathBeanDefinitionScanner(ctx, true, env).scan(PACKAGE);
            return new TreeSet<>(Set.of(ctx.getBeanDefinitionNames()));
        }
    }

    @Test
    void personalRegistersTheKeepListAndNoOperatorConsoleBean() {
        Set<String> beans =
                scannedBeanNames(
                        new MockEnvironment().withProperty("app.edition.tier", "personal"));
        assertThat(beans)
                .contains(
                        "platformController",
                        "llmUsageRollupScheduler",
                        "platformLlmTaskService",
                        "platformBroadcastService",
                        "maintenanceModeFilter",
                        "tenantSpendCapController",
                        "spendCapAlertNotifier")
                .doesNotContain(
                        "platformUserController",
                        "platformTenantDetailController",
                        "tenantDeletionController",
                        "impersonationService",
                        "abuseSignalService",
                        "platformBillingController",
                        "landingEventController",
                        "platformOpsController",
                        "fleetHealthController",
                        "northStarService",
                        "sloService",
                        "auditSealSweep",
                        "dataRetentionPurge");
    }

    @Test
    void personalDropsExactlyTheGatedClasses() throws ClassNotFoundException {
        Set<String> beans =
                scannedBeanNames(
                        new MockEnvironment().withProperty("app.edition.tier", "personal"));
        Set<String> gatedNames =
                components().stream()
                        .filter(OperatorConsoleEditionGateTest::gated)
                        .map(c -> java.beans.Introspector.decapitalize(c.getSimpleName()))
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(beans).doesNotContainAnyElementsOf(gatedNames);
    }

    @Test
    void everyOtherEditionRegistersTheOperatorConsole() throws ClassNotFoundException {
        Set<String> gatedNames =
                components().stream()
                        .filter(OperatorConsoleEditionGateTest::gated)
                        .map(c -> java.beans.Introspector.decapitalize(c.getSimpleName()))
                        .collect(Collectors.toCollection(TreeSet::new));
        // The two property-gated ones (LandingEventBuffer, WebhookAbuseAlertSink) have their own
        // switch, which
        // is not what this checks.
        gatedNames.removeAll(Set.of("landingEventBuffer", "webhookAbuseAlertSink"));
        assertThat(scannedBeanNames(new MockEnvironment())).containsAll(gatedNames);
        assertThat(scannedBeanNames(new MockEnvironment().withProperty("app.edition.tier", "lite")))
                .containsAll(gatedNames);
        assertThat(scannedBeanNames(new MockEnvironment().withProperty("app.edition.tier", "full")))
                .containsAll(gatedNames);
    }

    @Test
    void anExplicitFlagBringsTheOperatorConsoleBackOnPersonal() {
        Set<String> beans =
                scannedBeanNames(
                        new MockEnvironment()
                                .withProperty("app.edition.tier", "personal")
                                .withProperty(
                                        "app.edition.features.operator-console.enabled", "true"));
        assertThat(beans)
                .contains("platformUserController", "impersonationService", "abuseSignalService");
    }

    @Test
    void thePublicStatusPageStillHasItsAnnouncementSource() {
        // PublicStatusController (core/app) injects PlatformBroadcastService and
        // MaintenanceModeFilter reads it:
        // gating either would break the status endpoint every edition serves.
        assertThat(gated(PlatformBroadcastService.class)).isFalse();
        assertThat(gated(MaintenanceModeFilter.class)).isFalse();
        assertThat(gated(TenantSpendCapController.class)).isFalse();
        assertThat(gated(SpendCapAlertNotifier.class)).isFalse();
    }
}
