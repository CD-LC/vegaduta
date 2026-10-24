package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import archfixtures.beannames.a.BeanNameFixturesA;
import archfixtures.beannames.b.BeanNameFixturesB;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;

/**
 * Two beans with the same name fail context start (ConflictingBeanDefinitionException for two
 * scanned classes, BeanDefinitionOverrideException otherwise: Spring Boot disables overriding) -
 * which no module's unit tests catch, because none of them boot the full classpath. Same for two
 * JPA entities with the same entity name (Hibernate refuses to start). This test does not boot a
 * context (no DB needed): it reads the production bytecode of every core module on core/app's
 * classpath with ArchUnit and names each bean with Spring's own {@link
 * AnnotationBeanNameGenerator}. Regression for the 2026-08-17 moderation crashloop (a service, a
 * repository and an entity name each duplicated across core/guardrails and core/platform) and the
 * 2026-09-23 prod boot failure (tools.mcpregistry.McpRegistryConfiguration vs
 * mcp.registry.McpRegistryConfiguration). It replaces {@code scripts/ci/bean-name-collision.sh}.
 *
 * <p>One bean namespace holds:
 *
 * <ul>
 *   <li>scanned components (any {@code @Component}, meta-annotations included): an explicit name
 *       ({@code @Service("x")}) takes part like a default one, since two explicit "x" collide too;
 *   <li>Spring Data repository interfaces (top-level, not {@code @NoRepositoryBean}): they have no
 *       annotation to rename them, so the interface itself must be renamed;
 *   <li>{@code @Bean} methods of components that carry no {@code @Conditional} (nor
 *       {@code @Profile}, nor any {@code @ConditionalOn*}) on the method or its class. Conditional
 *       alternatives sharing a name are a deliberate pattern and are left to the boot check.
 * </ul>
 *
 * <p>Entity names are Hibernate's separate namespace: {@code @Entity(name = "...")} or the
 * unqualified class name. {@code scripts/ci/entity-name-lint.sh} still covers tables and controller
 * base paths, which have an allowlist.
 *
 * <p>Each check is proven against the fixtures in {@code archfixtures.beannames}, outside the
 * component-scan root so no test context picks them up.
 */
class UniqueBeanNamesTest {

    private static final String SPRING_DATA_REPOSITORY =
            "org.springframework.data.repository.Repository";
    private static final String NO_REPOSITORY_BEAN =
            "org.springframework.data.repository.NoRepositoryBean";

    private static final BeanNameGenerator NAMES = AnnotationBeanNameGenerator.INSTANCE;

    private static JavaClasses production;
    private static JavaClasses fixtures;

    @BeforeAll
    static void importClasses() {
        production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        fixtures =
                new ClassFileImporter()
                        .importPackagesOf(BeanNameFixturesA.class, BeanNameFixturesB.class);
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
    void noTwoBeansShareAName() {
        assertThat(beanNameClashes(production))
                .as(
                        "Spring bean names must be unique across modules: rename one class or give"
                                + " it an explicit name (@Service(\"...\")); a Spring Data"
                                + " repository interface has to be renamed")
                .isEmpty();
    }

    @Test
    void noTwoEntitiesShareAnEntityName() {
        assertThat(entityNameClashes(production))
                .as(
                        "JPA entity names must be unique across modules: rename one class or give"
                                + " it an explicit @Entity(name = \"...\")")
                .isEmpty();
    }

    @Test
    void theScanSeesTheProductionBeansAndEntities() {
        // Guards against a vacuous pass (wrong package, empty classpath).
        assertThat(beanNames(production)).hasSizeGreaterThan(500);
        assertThat(entityNames(production)).hasSizeGreaterThan(100);
    }

    // ---- negative tests ------------------------------------------------------------------

    @Test
    void reportsDuplicateComponentRepositoryAndBeanMethodNames() {
        assertThat(beanNameClashes(fixtures).keySet())
                .containsExactlyInAnyOrder(
                        "duplicateService",
                        "explicitlyNamedService",
                        "duplicateRepository",
                        "duplicateBean");
    }

    @Test
    void namesNestedClassesTheWaySpringDoes() {
        // Spring names a nested component "outer.Nested"; two Nested classes in different outers
        // do not collide (a simple-name heuristic would report them).
        assertThat(beanNames(fixtures)).containsKeys("holder.Nested", "otherHolder.Nested");
    }

    @Test
    void reportsDuplicateEntityNames() {
        assertThat(entityNameClashes(fixtures).keySet())
                .containsExactlyInAnyOrder("DuplicateEntity", "RenamedEntity");
    }

    // ---- the model -----------------------------------------------------------------------

    static Map<String, List<String>> beanNameClashes(JavaClasses classes) {
        return clashes(beanNames(classes));
    }

    static Map<String, List<String>> entityNameClashes(JavaClasses classes) {
        return clashes(entityNames(classes));
    }

    /** Bean name to the distinct definitions claiming it. */
    static Map<String, Set<String>> beanNames(JavaClasses classes) {
        Map<String, Set<String>> names = new TreeMap<>();
        var registry = new SimpleBeanDefinitionRegistry();
        for (JavaClass c : classes) {
            if (SpringWiring.isScannedComponent(c)) {
                Class<?> type = c.reflect();
                add(
                        names,
                        NAMES.generateBeanName(new AnnotatedGenericBeanDefinition(type), registry),
                        c.getName());
                for (JavaMethod m : c.getMethods()) {
                    if (!m.isAnnotatedWith(Bean.class)
                            || SpringWiring.isConditional(m)
                            || SpringWiring.isConditional(c)) {
                        continue;
                    }
                    // Overloads of one @Bean method are one definition: key by class + name.
                    String source = c.getName() + "#" + m.getName();
                    for (String name : beanMethodNames(m)) {
                        add(names, name, source);
                    }
                }
            } else if (isSpringDataRepository(c)) {
                Class<?> type = c.reflect();
                add(
                        names,
                        NAMES.generateBeanName(new AnnotatedGenericBeanDefinition(type), registry),
                        c.getName());
            }
        }
        return names;
    }

    /** Entity name to the classes claiming it (Hibernate's default is the unqualified name). */
    static Map<String, Set<String>> entityNames(JavaClasses classes) {
        Map<String, Set<String>> names = new TreeMap<>();
        for (JavaClass c : classes) {
            if (!c.isAnnotatedWith(Entity.class)) {
                continue;
            }
            String explicit = c.getAnnotationOfType(Entity.class).name();
            String name =
                    explicit.isEmpty()
                            ? c.getName().substring(c.getName().lastIndexOf('.') + 1)
                            : explicit;
            add(names, name, c.getName());
        }
        return names;
    }

    private static boolean isSpringDataRepository(JavaClass c) {
        // Spring Data does not register nested repository interfaces unless
        // considerNestedRepositories is set, which core never does.
        return c.isInterface()
                && c.isTopLevelClass()
                && c.isAssignableTo(SPRING_DATA_REPOSITORY)
                && !c.isAnnotatedWith(NO_REPOSITORY_BEAN);
    }

    private static List<String> beanMethodNames(JavaMethod m) {
        Bean bean = m.getAnnotationOfType(Bean.class);
        Set<String> explicit = new LinkedHashSet<>(List.of(bean.name()));
        explicit.addAll(List.of(bean.value()));
        return explicit.isEmpty() ? List.of(m.getName()) : List.copyOf(explicit);
    }

    private static void add(Map<String, Set<String>> names, String name, String source) {
        names.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(source);
    }

    private static Map<String, List<String>> clashes(Map<String, Set<String>> names) {
        Map<String, List<String>> clashes = new TreeMap<>();
        names.forEach(
                (name, sources) -> {
                    if (sources.size() > 1) {
                        clashes.put(name, new ArrayList<>(sources));
                    }
                });
        return clashes;
    }
}
