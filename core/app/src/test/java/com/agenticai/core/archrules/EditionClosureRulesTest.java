package com.agenticai.core.archrules;

import static com.agenticai.core.archrules.SpringWiring.constructors;
import static com.agenticai.core.archrules.SpringWiring.has;
import static com.agenticai.core.archrules.SpringWiring.isInjectionMarked;
import static org.assertj.core.api.Assertions.assertThat;

import archfixtures.edition.EditionFixtures;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The edition closure rule (AGENTS.md rule 9, for the lean edition): a bean gated by {@link
 * ConditionalOnEditionFeature} may be injected only by beans gated by the SAME feature, or through
 * {@code ObjectProvider}, {@code Optional} or a collection. Anything else compiles, unit-tests
 * cleanly and crashes core at startup on the edition that drops the gated bean ("No qualifying bean
 * of type ...").
 *
 * <p>ArchUnit over the production bytecode of every core module on core/app's classpath, no
 * context, no database. Gated classes are discovered by the annotation, so a package that gates a
 * class never edits this test. A required dependency is, in a Spring component or
 * {@code @Configuration}: a parameter of the constructor Spring will use (the only one, or the
 * marked one), of an {@code @Autowired}/{@code @Inject} method, or of a {@code @Bean} method; or an
 * {@code @Autowired}/{@code @Inject}/{@code @Resource} field. Those are exempt when
 * {@code @Nullable} or {@code @Autowired(required = false)}. A {@code @Bean} method counts as gated
 * when it carries the annotation itself.
 *
 * <p>What it does not see: a dependency on an interface or superclass whose only implementation is
 * gated, and a bean fetched by name or from the {@code ApplicationContext}. Those stay a review
 * concern.
 *
 * <p>Vacuous until a package gates its first class, and proven against {@link EditionFixtures}
 * either way.
 */
class EditionClosureRulesTest {

    private static final String NULLABLE = "Nullable";

    private static JavaClasses production;
    private static JavaClasses fixtures;

    @BeforeAll
    static void importClasses() {
        production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        fixtures = new ClassFileImporter().importPackagesOf(EditionFixtures.class);
    }

    @Test
    void noBeanInjectsAClassGatedByAnotherFeatureOrNoFeature() {
        assertThat(violations(production))
                .as(
                        "a bean gated by an edition feature may be injected only by beans gated by the"
                                + " SAME feature, or through ObjectProvider/Optional/List: gate the"
                                + " consumer with the same @ConditionalOnEditionFeature, or inject lazily")
                .isEmpty();
    }

    @Test
    void theScanSeesTheProductionComponents() {
        // Guards against a vacuous pass (wrong package, empty classpath): core has hundreds.
        assertThat(production.stream().filter(SpringWiring::isScannedComponent).count())
                .isGreaterThan(300);
    }

    // ---- negative tests: the rule reports exactly the broken fixtures ----------------------

    @Test
    void reportsEveryRequiredDependencyOnAGatedBeanFromOutsideItsFeature() {
        assertThat(violations(fixtures))
                .containsExactlyInAnyOrder(
                        EditionFixtures.UngatedConstructorInjection.class.getName()
                                + " -> constructor",
                        EditionFixtures.OtherFeatureConstructorInjection.class.getName()
                                + " -> constructor",
                        EditionFixtures.UngatedFieldInjection.class.getName() + " -> field speech",
                        EditionFixtures.UngatedSetterInjection.class.getName() + " -> setSpeech",
                        EditionFixtures.UngatedBillingConsumer.class.getName() + " -> constructor",
                        EditionFixtures.ConfigWithUngatedBeanMethod.class.getName()
                                + " -> ungatedBean");
    }

    @Test
    void theFixturesIncludeGatedBeansAndSanctionedConsumers() {
        assertThat(gatedClasses(fixtures))
                .extracting(JavaClass::getName)
                .contains(
                        EditionFixtures.GatedSocial.class.getName(),
                        EditionFixtures.GatedSpeech.class.getName(),
                        EditionFixtures.GatedBilling.class.getName());
        assertThat(violations(fixtures))
                .doesNotContain(
                        EditionFixtures.SameFeatureConsumer.class.getName() + " -> constructor",
                        EditionFixtures.OptionalAccess.class.getName() + " -> constructor",
                        EditionFixtures.NotRequiredField.class.getName() + " -> field social",
                        EditionFixtures.ConfigWithGatedBeanMethod.class.getName()
                                + " -> gatedBean");
    }

    // ---- the model -----------------------------------------------------------------------

    /**
     * Every required dependency of a component on a class gated by a different feature (or none).
     */
    static List<String> violations(JavaClasses classes) {
        List<String> found = new ArrayList<>();
        for (JavaClass c : classes) {
            if (!SpringWiring.isScannedComponent(c)) {
                continue;
            }
            Optional<EditionFeature> own = featureOf(c);
            for (JavaConstructor k : injectedConstructors(c)) {
                if (dependsOnForeignGatedClass(k, own)) {
                    found.add(c.getName() + " -> constructor");
                }
            }
            for (JavaMethod m : c.getMethods()) {
                boolean bean = m.isAnnotatedWith(org.springframework.context.annotation.Bean.class);
                boolean autowired = isInjectionMarked(m);
                if (!bean && !autowired) {
                    continue;
                }
                Optional<EditionFeature> gate = bean ? featureOf(m).or(() -> own) : own;
                if (dependsOnForeignGatedClass(m, gate)) {
                    found.add(c.getName() + " -> " + m.getName());
                }
            }
            for (JavaField f : c.getFields()) {
                if (isRequiredInjectedField(f)
                        && gatedFeatureOf(f.getRawType())
                                .filter(g -> !own.equals(Optional.of(g)))
                                .isPresent()) {
                    found.add(c.getName() + " -> field " + f.getName());
                }
            }
        }
        return found;
    }

    static List<JavaClass> gatedClasses(JavaClasses classes) {
        return classes.stream().filter(c -> featureOf(c).isPresent()).toList();
    }

    /** The constructor Spring will call: the only one, or the one marked for injection. */
    private static List<JavaConstructor> injectedConstructors(JavaClass c) {
        List<JavaConstructor> all = constructors(c);
        if (all.size() == 1) {
            return all;
        }
        return all.stream().filter(SpringWiring::isInjectionMarked).toList();
    }

    private static boolean dependsOnForeignGatedClass(
            JavaCodeUnit unit, Optional<EditionFeature> consumerGate) {
        for (JavaParameter p : unit.getParameters()) {
            if (isNullable(p)) {
                continue;
            }
            Optional<EditionFeature> dependency = gatedFeatureOf(p.getRawType());
            if (dependency.isPresent() && !consumerGate.equals(dependency)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRequiredInjectedField(JavaField f) {
        boolean marked =
                f.isAnnotatedWith("jakarta.inject.Inject")
                        || f.isAnnotatedWith("jakarta.annotation.Resource")
                        || has(f, "org.springframework.beans.factory.annotation.Autowired");
        if (!marked || isNullable(f)) {
            return false;
        }
        return f.tryGetAnnotationOfType(Autowired.class).map(Autowired::required).orElse(true);
    }

    private static boolean isNullable(HasAnnotations<?> element) {
        return element.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getSimpleName().equals(NULLABLE));
    }

    private static Optional<EditionFeature> gatedFeatureOf(JavaClass type) {
        return featureOf(type);
    }

    private static Optional<EditionFeature> featureOf(HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(ConditionalOnEditionFeature.class)
                .map(ConditionalOnEditionFeature::value);
    }
}
