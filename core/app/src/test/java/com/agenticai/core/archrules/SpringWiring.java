package com.agenticai.core.archrules;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import java.util.List;

/**
 * What Spring does with a class, answered from bytecode (ArchUnit) without starting a context.
 * Shared by {@link InjectableConstructorTest} and {@link UniqueBeanNamesTest}, the JVM-level checks
 * for the wiring failures that compile and unit-test cleanly but stop core at startup (AGENTS.md
 * rule 9).
 */
final class SpringWiring {

    static final String COMPONENT = "org.springframework.stereotype.Component";
    static final String CONDITIONAL = "org.springframework.context.annotation.Conditional";
    static final String CONFIGURATION_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    static final String CONSTRUCTOR_BINDING =
            "org.springframework.boot.context.properties.bind.ConstructorBinding";
    static final String VALUE = "org.springframework.beans.factory.annotation.Value";

    /**
     * The annotations {@code AutowiredAnnotationBeanPostProcessor} treats as "inject through this
     * constructor" ({@code @Value} on a constructor is one of them).
     */
    static final List<String> INJECTION_MARKERS =
            List.of(
                    "org.springframework.beans.factory.annotation.Autowired",
                    VALUE,
                    "jakarta.inject.Inject",
                    "javax.inject.Inject");

    private SpringWiring() {}

    /** Directly or meta-annotated (a {@code @Service} is a {@code @Component}). */
    static boolean has(HasAnnotations<?> element, String annotation) {
        return element.isAnnotatedWith(annotation) || element.isMetaAnnotatedWith(annotation);
    }

    /**
     * A class Spring's component scan registers: a stereotype on a concrete, independent class (top
     * level or static nested), the same candidates {@code
     * ClassPathScanningCandidateComponentProvider} accepts.
     */
    static boolean isScannedComponent(JavaClass c) {
        return has(c, COMPONENT) && isConcreteIndependentClass(c);
    }

    /** A {@code @ConfigurationProperties} class that is not also a scanned component. */
    static boolean isConfigurationPropertiesOnly(JavaClass c) {
        return c.isAnnotatedWith(CONFIGURATION_PROPERTIES)
                && !has(c, COMPONENT)
                && isConcreteIndependentClass(c);
    }

    static boolean isConcreteIndependentClass(JavaClass c) {
        if (c.isInterface()
                || c.isEnum()
                || c.isAnnotation()
                || c.isAnonymousClass()
                || c.isLocalClass()
                || c.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        return c.isTopLevelClass() || c.getModifiers().contains(JavaModifier.STATIC);
    }

    /** Declared constructors, private ones included (Spring considers them), synthetic excluded. */
    static List<JavaConstructor> constructors(JavaClass c) {
        return c.getConstructors().stream()
                .filter(k -> !k.getModifiers().contains(JavaModifier.SYNTHETIC))
                .toList();
    }

    static boolean isInjectionMarked(JavaCodeUnit unit) {
        return INJECTION_MARKERS.stream().anyMatch(a -> has(unit, a));
    }

    /**
     * True when the class or the member carries {@code @Conditional} ({@code @Profile} and every
     * {@code @ConditionalOn*} do).
     */
    static boolean isConditional(HasAnnotations<?> element) {
        return has(element, CONDITIONAL);
    }
}
