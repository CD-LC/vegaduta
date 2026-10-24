package com.agenticai.core.archrules;

import static com.agenticai.core.archrules.SpringWiring.constructors;
import static com.agenticai.core.archrules.SpringWiring.has;
import static com.agenticai.core.archrules.SpringWiring.isInjectionMarked;
import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import archfixtures.constructors.ConstructorFixtures;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A scanned component with MORE THAN ONE constructor and no {@code @Autowired} on any of them fails
 * at context start with "No default constructor found". Unit tests never build the context, so this
 * shipped to prod twice on 2026-09-24 ({@code ResolutionPricingService}, then {@code
 * TenantMigrationReadinessIndicator}) and to stage on 2026-10-15 ({@code HostedToolCallGradeProbe})
 * - each time as a revision that crash-looped while the previous one kept serving. ArchUnit over
 * the production bytecode of every core module on core/app's classpath: no context, no DB, runs in
 * the normal {@code mvn test}. It replaces {@code scripts/ci/spring-constructor-lint.sh}.
 *
 * <p>Spring's rule ({@code AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors}):
 * with one constructor Spring uses it; with several it uses the one marked {@code @Autowired} (or
 * {@code @Inject} / {@code @Value}), and otherwise falls back to a no-arg constructor, failing when
 * there is none. Private constructors count; synthetic ones do not. Records get no special
 * treatment from component scanning, so they are checked like any class.
 *
 * <p>The second rule covers {@code @ConfigurationProperties} classes registered through
 * {@code @EnableConfigurationProperties}: with several constructors Spring Boot binds through the
 * one marked {@code @ConstructorBinding}; otherwise it binds JavaBean style and Spring needs a
 * no-arg (or {@code @Autowired}) constructor to create the object.
 *
 * <p>The third rule covers the quiet variant: a no-arg constructor next to one taking
 * {@code @Value} parameters and no {@code @Autowired}. Spring boots fine but uses the no-arg one,
 * so the configuration is silently ignored ({@code McpStdioCommandGuard} dropped {@code
 * app.mcp.stdio-launcher-allowlist} this way until 2026-10-14).
 *
 * <p>Each rule is proven against {@link ConstructorFixtures}, which lives outside {@code
 * com.agenticai.core} so no Spring context in the test suite scans its deliberately broken beans.
 *
 * <p>Constructor cycles are the other startup-only wiring failure and need a real context: {@code
 * CoreApplicationTests} (CI, with Postgres) and {@code scripts/ci/boot-check.sh} ({@code make
 * boot-check}).
 */
class InjectableConstructorTest {

    static final ArchRule SPRING_CAN_CHOOSE_A_CONSTRUCTOR =
            classes()
                    .that(scannedComponents())
                    .should(haveAConstructorSpringCanChoose())
                    .as(
                            "a Spring component with several constructors must mark the one to"
                                    + " inject with @Autowired (or keep a single constructor)")
                    .because(
                            "Spring cannot choose and fails the context with 'No default"
                                    + " constructor found'; no unit test builds the context");

    static final ArchRule CONFIGURATION_PROPERTIES_HAVE_A_BINDABLE_CONSTRUCTOR =
            classes()
                    .that(configurationPropertiesOnly())
                    .should(haveAConstructorBootCanBind())
                    .as(
                            "a @ConfigurationProperties class with several constructors must mark"
                                    + " one @ConstructorBinding (or @Autowired) or keep a no-arg one")
                    .because(
                            "Spring Boot then binds JavaBean style and cannot create the object"
                                    + " without a no-arg constructor")
                    .allowEmptyShould(true);

    static final ArchRule NO_SILENT_NO_ARG_FALLBACK =
            classes()
                    .that(scannedComponents())
                    .should(notFallBackToANoArgConstructorIgnoringValueParameters())
                    .as(
                            "a Spring component must not have a no-arg constructor next to an"
                                    + " unmarked constructor taking @Value parameters")
                    .because(
                            "Spring uses the no-arg constructor and silently ignores the"
                                    + " configuration; annotate the configured one with"
                                    + " @Autowired");

    private static JavaClasses production;
    private static JavaClasses fixtures;

    @BeforeAll
    static void importClasses() {
        production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        fixtures = new ClassFileImporter().importPackagesOf(ConstructorFixtures.class);
    }

    /**
     * Releases the imported class graphs. Surefire runs all of this module's tests in one JVM, so
     * the statics of a finished test class would otherwise stay on the heap for the rest of the
     * run, and the other ArchUnit tests import the classpath again.
     */
    @AfterAll
    static void releaseImports() {
        production = null;
        fixtures = null;
    }

    @Test
    void everyScannedComponentWithSeveralConstructorsMarksTheInjectableOne() {
        SPRING_CAN_CHOOSE_A_CONSTRUCTOR.check(production);
    }

    @Test
    void everyConfigurationPropertiesClassWithSeveralConstructorsCanBeBound() {
        CONFIGURATION_PROPERTIES_HAVE_A_BINDABLE_CONSTRUCTOR.check(production);
    }

    @Test
    void noComponentFallsBackToANoArgConstructorWhileAnotherTakesConfiguration() {
        NO_SILENT_NO_ARG_FALLBACK.check(production);
    }

    @Test
    void theScanSeesTheProductionComponents() {
        // Guards against a vacuous pass (wrong package, empty classpath): core has hundreds.
        assertThat(production.stream().filter(SpringWiring::isScannedComponent).count())
                .isGreaterThan(300);
    }

    // ---- negative tests: each rule reports exactly the broken fixtures --------------------

    @Test
    void reportsAComponentWithTwoUnmarkedConstructors() {
        assertThat(violatingClasses(SPRING_CAN_CHOOSE_A_CONSTRUCTOR))
                .containsExactlyInAnyOrder(
                        ConstructorFixtures.TwoConstructorsNoAutowired.class.getName(),
                        ConstructorFixtures.PublicAndPrivateConstructor.class.getName(),
                        ConstructorFixtures.ServiceWithTwoConstructors.class.getName(),
                        ConstructorFixtures.RecordComponentWithTwoConstructors.class.getName());
    }

    @Test
    void reportsAConfigurationPropertiesClassBootCannotBind() {
        assertThat(violatingClasses(CONFIGURATION_PROPERTIES_HAVE_A_BINDABLE_CONSTRUCTOR))
                .containsExactly(ConstructorFixtures.PropertiesWithTwoConstructors.class.getName());
    }

    @Test
    void reportsANoArgConstructorThatShadowsAConfiguredOne() {
        assertThat(violatingClasses(NO_SILENT_NO_ARG_FALLBACK))
                .containsExactly(ConstructorFixtures.NoArgShadowsValueConstructor.class.getName());
    }

    private static List<String> violatingClasses(ArchRule rule) {
        EvaluationResult result = rule.evaluate(fixtures);
        return fixtures.stream()
                .map(JavaClass::getName)
                .filter(
                        name ->
                                result.getFailureReport().getDetails().stream()
                                        .anyMatch(d -> d.startsWith("Class <" + name + ">")))
                .toList();
    }

    // ---- predicates and conditions --------------------------------------------------------

    private static DescribedPredicate<JavaClass> scannedComponents() {
        return DescribedPredicate.describe(
                "are Spring components picked up by component scanning",
                SpringWiring::isScannedComponent);
    }

    private static DescribedPredicate<JavaClass> configurationPropertiesOnly() {
        return DescribedPredicate.describe(
                "are @ConfigurationProperties classes that are not components",
                SpringWiring::isConfigurationPropertiesOnly);
    }

    private static boolean hasNoArgConstructor(List<JavaConstructor> ctors) {
        return ctors.stream().anyMatch(k -> k.getRawParameterTypes().isEmpty());
    }

    private static ArchCondition<JavaClass> haveAConstructorSpringCanChoose() {
        return new ArchCondition<>("have a constructor Spring can choose") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                List<JavaConstructor> ctors = constructors(c);
                if (ctors.size() <= 1
                        || ctors.stream().anyMatch(SpringWiring::isInjectionMarked)
                        || hasNoArgConstructor(ctors)) {
                    return;
                }
                events.add(
                        violated(
                                c,
                                "Class <"
                                        + c.getName()
                                        + "> has "
                                        + ctors.size()
                                        + " constructors, none marked @Autowired and no no-arg"
                                        + " one: Spring cannot choose ('No default constructor"
                                        + " found')"));
            }
        };
    }

    private static ArchCondition<JavaClass> haveAConstructorBootCanBind() {
        return new ArchCondition<>("have a constructor Spring Boot can bind") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                List<JavaConstructor> ctors = constructors(c);
                if (ctors.size() <= 1
                        || ctors.stream()
                                .anyMatch(
                                        k ->
                                                isInjectionMarked(k)
                                                        || has(k, SpringWiring.CONSTRUCTOR_BINDING))
                        || hasNoArgConstructor(ctors)) {
                    return;
                }
                events.add(
                        violated(
                                c,
                                "Class <"
                                        + c.getName()
                                        + "> has "
                                        + ctors.size()
                                        + " constructors, none marked @ConstructorBinding and no"
                                        + " no-arg one"));
            }
        };
    }

    private static ArchCondition<JavaClass>
            notFallBackToANoArgConstructorIgnoringValueParameters() {
        return new ArchCondition<>("not fall back to a no-arg constructor") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                List<JavaConstructor> ctors = constructors(c);
                if (ctors.size() <= 1
                        || ctors.stream().anyMatch(SpringWiring::isInjectionMarked)
                        || !hasNoArgConstructor(ctors)) {
                    return;
                }
                boolean configured =
                        ctors.stream()
                                .flatMap(k -> k.getParameters().stream())
                                .anyMatch(p -> has(p, SpringWiring.VALUE));
                if (configured) {
                    events.add(
                            violated(
                                    c,
                                    "Class <"
                                            + c.getName()
                                            + "> has a no-arg constructor and an unmarked one"
                                            + " taking @Value parameters: Spring uses the no-arg"
                                            + " one and ignores the configuration"));
                }
            }
        };
    }
}
