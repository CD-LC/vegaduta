package com.agenticai.core.archrules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * Only web code may inject a controller or controller advice.
 *
 * <p>The jobs and worker shapes ({@code JobsApplication}, {@code WorkerApplication}) exclude every
 * {@code @Controller}/{@code @RestController} from component scanning. A service that injects a
 * controller therefore compiles, passes unit tests and boots the API, then stops both background
 * processes at startup ("required a bean of type ...Controller that could not be found"); that
 * happened with {@code MemoryImportService} and {@code UserMemoryController}. Shared logic belongs
 * in a service both can call.
 *
 * <p>The rule covers what breaks a shape at startup: a controller as a field or constructor
 * parameter of a non-controller, including type arguments ({@code ObjectProvider<XController>}) and
 * {@code @Bean}/{@code @Autowired} method parameters. Static helper calls into a controller (a
 * layering smell, not a startup failure) are not checked yet; a few exist and are worth moving to
 * plain classes (ADR-0023 moved {@code midStreamFailureMessage} from {@code AgentController} to
 * {@code AgentChatStreamResponder}).
 */
class ControllerLayeringRulesTest {

    /**
     * Web-layer beans: {@code @Controller}/{@code @RestController} and
     * {@code @ControllerAdvice}/{@code @RestControllerAdvice} (the shapes exclude those too).
     */
    private static final DescribedPredicate<JavaClass> A_CONTROLLER =
            DescribedPredicate.describe(
                    "a web-layer bean (controller or controller advice)",
                    c ->
                            c.isMetaAnnotatedWith(Controller.class)
                                    || c.isMetaAnnotatedWith(ControllerAdvice.class));

    @Test
    void nonControllersNeverInjectAController() {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        List<String> violations = violations(classes);
        assertTrue(
                violations.isEmpty(),
                "Non-controllers inject controllers (the jobs and worker shapes do not load"
                        + " controllers; move the shared logic into a service): "
                        + violations);
    }

    // ---- the rule must catch what it claims to (fixtures; the real scan skips test classes) ----

    @org.springframework.web.bind.annotation.RestController
    static class FixtureController {}

    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class FixtureAdvice {}

    static class InjectsControllerField {
        FixtureController controller;
    }

    static class InjectsControllerViaProvider {
        org.springframework.beans.factory.ObjectProvider<FixtureController> controller;
    }

    static class InjectsAdviceViaConstructor {
        InjectsAdviceViaConstructor(FixtureAdvice advice) {}
    }

    static class InjectsControllerViaListInBeanMethod {
        @Bean
        Object thing(List<FixtureController> controllers) {
            return controllers;
        }
    }

    static class InjectsControllerViaSetter {
        @Autowired
        void setController(FixtureController controller) {}
    }

    static class InjectsNothingBad {
        String name;
        static FixtureController staticHelperIsNotInjection;
    }

    @Test
    void theRuleCatchesEveryWayAShapeBreakingInjectionCanBeSpelled() {
        JavaClasses classes =
                new ClassFileImporter()
                        .importClasses(
                                InjectsControllerField.class, InjectsControllerViaProvider.class,
                                InjectsAdviceViaConstructor.class,
                                        InjectsControllerViaListInBeanMethod.class,
                                InjectsControllerViaSetter.class, InjectsNothingBad.class,
                                FixtureController.class, FixtureAdvice.class);

        List<String> violations = violations(classes);

        assertTrue(
                violations.stream().anyMatch(v -> v.contains("InjectsControllerField")),
                violations.toString());
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("InjectsControllerViaProvider")),
                violations.toString());
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("InjectsAdviceViaConstructor")),
                violations.toString());
        assertTrue(
                violations.stream()
                        .anyMatch(v -> v.contains("InjectsControllerViaListInBeanMethod")),
                violations.toString());
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("InjectsControllerViaSetter")),
                violations.toString());
        assertTrue(
                violations.stream().noneMatch(v -> v.contains("InjectsNothingBad")),
                violations.toString());
    }

    private static List<String> violations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : classes) {
            // Anonymous and nested classes of a controller are part of that controller.
            if (A_CONTROLLER.test(c)
                    || c.isAnnotation()
                    || c.getEnclosingClass().map(A_CONTROLLER::test).orElse(false)) {
                continue;
            }
            for (JavaField f : c.getFields()) {
                if (!f.getModifiers().contains(JavaModifier.STATIC)
                        && involvesController(f.getType())) {
                    violations.add(
                            c.getName() + " field " + f.getName() + " : " + f.getType().getName());
                }
            }
            for (JavaCodeUnit k : c.getCodeUnits()) {
                // Constructors, plus any method Spring calls to inject or build a bean.
                boolean injectionPoint =
                        k instanceof JavaConstructor
                                || k.isAnnotatedWith(Bean.class)
                                || k.isAnnotatedWith(Autowired.class);
                if (!injectionPoint) {
                    continue;
                }
                for (JavaType p : k.getParameterTypes()) {
                    if (involvesController(p)) {
                        violations.add(
                                c.getName() + " " + k.getName() + " parameter : " + p.getName());
                    }
                }
            }
        }
        return violations;
    }

    /**
     * The type itself or any type argument ({@code ObjectProvider<XController>}, {@code
     * List<...>}).
     */
    private static boolean involvesController(JavaType type) {
        return type.getAllInvolvedRawTypes().stream().anyMatch(A_CONTROLLER);
    }
}
