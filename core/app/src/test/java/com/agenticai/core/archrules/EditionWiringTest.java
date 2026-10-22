package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import archfixtures.edition.EditionFixtures;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.edition.EditionFeatureCondition;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Every class carrying {@link ConditionalOnEditionFeature} is discovered from the production bytecode (no
 * table to edit when a package gates a class) and its real {@code @Conditional} is evaluated against the
 * edition property sets: tier {@code personal} registers none of the gated classes (billing: when
 * {@code app.edition.billing-enabled} is false too), the default and {@code lite}/{@code full} register
 * all of them, and an explicit {@code app.edition.features.<name>.enabled} wins both ways.
 *
 * <p>Evaluating the condition on the class's own metadata needs no database or Redis and ignores any other
 * condition or profile on the class, so it holds for classes that are also {@code @Profile}-gated. The
 * registration check ({@code AnnotatedBeanDefinitionReader}, like {@link BillingEditionWiringTest}) runs on the
 * fixtures, whose only condition is the edition one.
 *
 * <p>Passes vacuously on the production tree until a package gates its first class; the fixtures keep the
 * test honest meanwhile. {@link BillingEditionWiringTest} keeps covering the nine {@code
 * ConditionalOnBillingEdition} leaf beans.
 */
class EditionWiringTest {

    private static final Map<String, Object> PERSONAL =
            Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "false");

    private static List<JavaClass> productionGated;
    private static List<JavaClass> fixtureGated;

    @BeforeAll
    static void discover() {
        JavaClasses production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        productionGated = EditionClosureRulesTest.gatedClasses(production);
        fixtureGated =
                EditionClosureRulesTest.gatedClasses(
                        new ClassFileImporter().importPackagesOf(EditionFixtures.class));
    }

    private static StandardEnvironment env(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return env;
    }

    private static boolean conditionMatches(JavaClass c, Map<String, Object> props) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(env(props));
        return new EditionFeatureCondition().matches(context, AnnotationMetadata.introspect(c.reflect()));
    }

    private static EditionFeature featureOf(JavaClass c) {
        return c.getAnnotationOfType(ConditionalOnEditionFeature.class).value();
    }

    private static int registered(Map<String, Object> props, List<JavaClass> classes) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.setEnvironment(env(props));
        new AnnotatedBeanDefinitionReader(ctx).register(classes.stream().map(JavaClass::reflect).toArray(Class<?>[]::new));
        try (ctx) {
            return (int)
                    Arrays.stream(ctx.getBeanDefinitionNames())
                            .map(n -> ctx.getBeanDefinition(n).getBeanClassName())
                            .filter(c -> c != null && classes.stream().anyMatch(k -> k.getName().equals(c)))
                            .count();
        }
    }

    @Test
    void defaultKeepsEveryGatedClass() {
        for (JavaClass c : productionGated) {
            assertThat(conditionMatches(c, Map.of())).as("%s with no edition property", c.getName()).isTrue();
        }
        assertThat(registered(Map.of(), fixtureGated)).isEqualTo(fixtureGated.size());
    }

    @Test
    void liteAndFullKeepEveryGatedClass() {
        for (String tier : List.of("lite", "full")) {
            for (JavaClass c : productionGated) {
                assertThat(conditionMatches(c, Map.of("app.edition.tier", tier)))
                        .as("%s on %s", c.getName(), tier)
                        .isTrue();
            }
            assertThat(registered(Map.of("app.edition.tier", tier), fixtureGated)).isEqualTo(fixtureGated.size());
        }
    }

    @Test
    void personalDropsEveryGatedClass() {
        for (JavaClass c : productionGated) {
            assertThat(conditionMatches(c, PERSONAL)).as("%s on personal", c.getName()).isFalse();
        }
        assertThat(registered(PERSONAL, fixtureGated)).isZero();
    }

    @Test
    void personalWithBillingOnKeepsOnlyTheBillingClasses() {
        Map<String, Object> props = Map.of("app.edition.tier", "personal", "app.edition.billing-enabled", "true");
        for (JavaClass c : productionGated) {
            assertThat(conditionMatches(c, props))
                    .as("%s on personal with billing on", c.getName())
                    .isEqualTo(featureOf(c) == EditionFeature.BILLING_PAYMENTS);
        }
        long billing = fixtureGated.stream().filter(c -> featureOf(c) == EditionFeature.BILLING_PAYMENTS).count();
        assertThat(billing).isPositive();
        assertThat(registered(props, fixtureGated)).isEqualTo((int) billing);
    }

    @Test
    void anExplicitFlagFlipsExactlyItsFeatureOnPersonalAndOffElsewhere() {
        for (EditionFeature feature : EditionFeature.values()) {
            Map<String, Object> on = new java.util.HashMap<>(PERSONAL);
            on.put(feature.propertyName(), "true");
            Map<String, Object> off = Map.of(feature.propertyName(), "false");
            for (JavaClass c : productionGated) {
                assertThat(conditionMatches(c, on))
                        .as("%s with %s forced on, personal", c.getName(), feature.id())
                        .isEqualTo(featureOf(c) == feature);
                assertThat(conditionMatches(c, off))
                        .as("%s with %s forced off, default tier", c.getName(), feature.id())
                        .isEqualTo(featureOf(c) != feature);
            }
            List<JavaClass> ofFeature = fixtureGated.stream().filter(c -> featureOf(c) == feature).toList();
            assertThat(registered(on, fixtureGated)).as("fixtures with %s forced on", feature.id()).isEqualTo(ofFeature.size());
        }
    }

    @Test
    void theFixturesCoverMoreThanOneFeature() {
        assertThat(fixtureGated.stream().map(EditionWiringTest::featureOf).distinct().count()).isGreaterThan(1);
    }
}
