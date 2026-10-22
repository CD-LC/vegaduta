package com.agenticai.core.archrules;

import static com.agenticai.core.archrules.SpringWiring.constructors;
import static com.agenticai.core.archrules.SpringWiring.has;
import static com.agenticai.core.archrules.SpringWiring.isScannedComponent;
import static org.assertj.core.api.Assertions.assertThat;

import archfixtures.shapes.ShapeFixtures;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The jobs and worker shapes ({@code JobsApplication}, {@code WorkerApplication}) scan the same
 * packages as the API but exclude every {@code @RestController}, {@code @Controller} and
 * {@code @ControllerAdvice}. A scanned component that REQUIRES a bean only such a controller
 * provides therefore fails the whole context at start ("Parameter 0 of constructor in
 * ApiGuideToolSource required a bean of type ApiSpecProvider that could not be found"): the API
 * boots, every unit test passes, and the jobs and worker images of v0.1.9 crash-looped. No module's
 * tests build that context (it needs Postgres), so this test reads the production bytecode of every
 * core module with ArchUnit, like {@link InjectableConstructorTest}: it takes the beans the shapes'
 * scan keeps (scanned components, {@code @Bean} methods, {@code @ConfigurationProperties} classes),
 * and for each REQUIRED injection point of a core type (a constructor parameter, an
 * {@code @Autowired} field or setter, a {@code @Bean} method parameter) asks whether a kept bean
 * can satisfy it.
 *
 * <p>An injection point is not required when it is an {@code ObjectProvider}, {@code Optional},
 * {@code Provider}, {@code ObjectFactory}, a collection, array or map, an {@code @Value} parameter,
 * a {@code @Nullable} one, or an {@code @Autowired(required = false)} member. The fix for a finding
 * here is one of those (as {@code ApiGuideToolSource} now does), or moving the provider out of a
 * controller.
 *
 * <p>Only the failure above is reported: a required type that a controller implements and no kept
 * bean does. A type nothing implements is left alone, because beans also come from places bytecode
 * does not show (Spring Data repositories, auto-configuration). Nor does this evaluate
 * {@code @Conditional}s or profiles (a provider that exists only under another profile counts) or
 * match {@code @Qualifier}s. It is not a substitute for a boot: {@code scripts/ci/boot-check.sh}
 * boots the real api, jobs and worker jars.
 *
 * <p>{@code agents-chatport} and {@code brief} are on the API's classpath but not on the jobs and
 * worker ones, so their packages are left out of the shape; {@link
 * #theShapesAndTheApiDifferOnlyByTheApiOnlyModules} fails when the three poms drift. Each rule is
 * proven against {@link ShapeFixtures}.
 */
class ShapeInjectionPointsTest {

    private static final String CORE_TYPES = "com.agenticai.";
    private static final String FIXTURE_TYPES = "archfixtures.shapes.";
    private static final String BEAN = "org.springframework.context.annotation.Bean";
    private static final String AUTOWIRED =
            "org.springframework.beans.factory.annotation.Autowired";
    private static final String NULLABLE = "Nullable";
    private static final List<String> CONTROLLER_STEREOTYPES =
            List.of(
                    "org.springframework.stereotype.Controller",
                    "org.springframework.web.bind.annotation.RestController",
                    "org.springframework.web.bind.annotation.ControllerAdvice");

    /** Packages of modules the API has and jobs/worker have not (their poms differ by these). */
    private static final List<String> API_ONLY_PACKAGES =
            List.of("com.agenticai.core.agents.chatport.", "com.agenticai.core.brief.");

    /** Types Spring resolves lazily or tolerates an absent bean for. */
    private static final Set<String> OPTIONAL_WRAPPERS =
            Set.of(
                    "org.springframework.beans.factory.ObjectProvider",
                    "org.springframework.beans.factory.ObjectFactory",
                    "java.util.Optional",
                    "jakarta.inject.Provider",
                    "javax.inject.Provider");

    private static JavaClasses production;
    private static JavaClasses fixtures;

    @BeforeAll
    static void importClasses() {
        production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ModuleBoundaryRulesTest.ROOT);
        fixtures = new ClassFileImporter().importPackagesOf(ShapeFixtures.class);
    }

    @Test
    void noScannedComponentRequiresABeanOnlyAControllerProvides() {
        List<String> problems = unsatisfied(production, CORE_TYPES, API_ONLY_PACKAGES);

        assertThat(problems)
                .as(
                        "required injection points the jobs and worker shapes cannot satisfy:"
                                + " make the dependency optional (ObjectProvider) or move the"
                                + " provider out of a controller")
                .isEmpty();
    }

    @Test
    void theScanSeesTheProductionBeans() {
        // Guards against a vacuous pass (wrong package, empty classpath).
        assertThat(production.stream().filter(ShapeScan::isKept).count()).isGreaterThan(300);
        assertThat(production.stream().filter(ShapeScan::isController).count()).isGreaterThan(50);
    }

    @Test
    void theShapesAndTheApiDifferOnlyByTheApiOnlyModules() throws Exception {
        Set<String> api = coreDependencies(Path.of("pom.xml"));
        Set<String> jobs = coreDependencies(Path.of("..", "app-jobs", "pom.xml"));
        Set<String> worker = coreDependencies(Path.of("..", "app-worker", "pom.xml"));

        assertThat(worker).as("app-worker and app-jobs are deliberate copies").isEqualTo(jobs);
        Set<String> apiOnly = new TreeSet<>(api);
        apiOnly.removeAll(jobs);
        assertThat(apiOnly)
                .as(
                        "modules on the API's classpath but not on the jobs/worker ones: keep"
                                + " API_ONLY_PACKAGES in step")
                .containsExactly("agents-chatport", "brief");
    }

    // ---- negative tests: the rule reports exactly the broken fixtures ---------------------

    @Test
    void reportsEveryRequiredInjectionPointOnlyAControllerSatisfies() {
        List<String> problems = unsatisfied(fixtures, FIXTURE_TYPES, List.of());

        assertThat(problems)
                .anyMatch(p -> p.startsWith("RequiresControllerProvidedBean constructor"))
                .anyMatch(p -> p.startsWith("FieldRequiresControllerProvidedBean field"))
                .anyMatch(p -> p.startsWith("BeanMethodRequiresControllerProvidedBean @Bean"))
                .hasSize(3);
    }

    @Test
    void acceptsOptionalAndSatisfiedInjectionPointsAndControllersThemselves() {
        List<String> problems = unsatisfied(fixtures, FIXTURE_TYPES, List.of());

        assertThat(problems)
                .noneMatch(p -> p.startsWith("OptionalThrough"))
                .noneMatch(p -> p.startsWith("ValueParameter"))
                .noneMatch(p -> p.startsWith("RequiresPlainProvider"))
                .noneMatch(p -> p.startsWith("RequiresBeanMethodProvider"))
                .noneMatch(p -> p.startsWith("ControllerRequiresSpec"))
                .noneMatch(p -> p.startsWith("RequiresAbsentBean"));
    }

    @Test
    void aClassOutsideTheShapesClasspathIsNotChecked() {
        List<String> problems =
                unsatisfied(
                        fixtures,
                        FIXTURE_TYPES,
                        List.of(ShapeFixtures.RequiresControllerProvidedBean.class.getName()));

        assertThat(problems)
                .hasSize(2)
                .noneMatch(p -> p.startsWith("RequiresControllerProvidedBean"));
    }

    // ---- the check --------------------------------------------------------------------------

    /**
     * One line per required injection point, of a type whose name starts with {@code typePrefix},
     * that a controller could satisfy but no bean of the shape can; classes whose name starts with
     * one of {@code absentPrefixes} are not on the shape's classpath.
     */
    private static List<String> unsatisfied(
            JavaClasses classes, String typePrefix, List<String> absentPrefixes) {
        List<JavaClass> shape =
                classes.stream()
                        .filter(c -> absentPrefixes.stream().noneMatch(c.getName()::startsWith))
                        .toList();
        List<JavaClass> beanTypes = new ArrayList<>();
        for (JavaClass c : shape) {
            if (!ShapeScan.isKept(c)) {
                continue;
            }
            beanTypes.add(c);
            for (JavaMethod m : c.getMethods()) {
                if (m.isAnnotatedWith(BEAN)) {
                    beanTypes.add(m.getRawReturnType());
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (JavaClass c : shape) {
            if (!ShapeScan.isKept(c)) {
                continue;
            }
            for (InjectionPoint point : injectionPoints(c)) {
                JavaClass type = point.type();
                if (point.optional()
                        || !type.getName().startsWith(typePrefix)
                        || beanTypes.stream().anyMatch(b -> b.isAssignableTo(type.getName()))
                        || !providedByAController(shape, type)) {
                    continue;
                }
                problems.add(
                        c.getSimpleName()
                                + " "
                                + point.where()
                                + " requires "
                                + type.getName()
                                + ", which only a controller provides and the jobs and worker"
                                + " shapes scan none");
            }
        }
        return problems;
    }

    private static boolean providedByAController(List<JavaClass> shape, JavaClass type) {
        return shape.stream()
                .anyMatch(c -> ShapeScan.isController(c) && c.isAssignableTo(type.getName()));
    }

    private record InjectionPoint(String where, JavaClass type, boolean optional) {}

    private static List<InjectionPoint> injectionPoints(JavaClass c) {
        List<InjectionPoint> points = new ArrayList<>();
        List<JavaConstructor> ctors = constructors(c);
        JavaConstructor chosen =
                ctors.size() == 1
                        ? ctors.get(0)
                        : ctors.stream()
                                .filter(SpringWiring::isInjectionMarked)
                                .findFirst()
                                .orElse(null);
        if (chosen != null && isScannedComponent(c)) {
            addParameters(points, "constructor", chosen.getParameters(), requiredMember(chosen));
        }
        for (JavaMethod m : c.getMethods()) {
            if (m.isAnnotatedWith(BEAN)) {
                addParameters(points, "@Bean " + m.getName(), m.getParameters(), true);
            } else if (isScannedComponent(c) && m.isAnnotatedWith(AUTOWIRED)) {
                addParameters(
                        points, "method " + m.getName(), m.getParameters(), requiredMember(m));
            }
        }
        if (isScannedComponent(c)) {
            for (JavaField f : c.getFields()) {
                if (f.isAnnotatedWith(AUTOWIRED)) {
                    points.add(
                            new InjectionPoint(
                                    "field " + f.getName(),
                                    f.getRawType(),
                                    !requiredMember(f) || isOptionalType(f.getRawType())));
                }
            }
        }
        return points;
    }

    private static void addParameters(
            List<InjectionPoint> points,
            String where,
            List<JavaParameter> parameters,
            boolean memberRequired) {
        for (JavaParameter p : parameters) {
            boolean optional =
                    !memberRequired
                            || isOptionalType(p.getRawType())
                            || has(p, SpringWiring.VALUE)
                            || p.getAnnotations().stream()
                                    .anyMatch(a -> a.getRawType().getSimpleName().equals(NULLABLE));
            points.add(new InjectionPoint(where, p.getRawType(), optional));
        }
    }

    /** {@code @Autowired(required = false)} on the member. */
    private static boolean requiredMember(HasAnnotations<?> member) {
        return member.tryGetAnnotationOfType(AUTOWIRED)
                .map(a -> !Boolean.FALSE.equals(a.get("required").orElse(true)))
                .orElse(true);
    }

    private static boolean isOptionalType(JavaClass type) {
        return type.isArray()
                || OPTIONAL_WRAPPERS.contains(type.getName())
                || type.isAssignableTo(java.util.Collection.class)
                || type.isAssignableTo(java.util.Map.class);
    }

    /** What the jobs and worker shapes keep and drop, from {@code @ComponentScan}'s filters. */
    private static final class ShapeScan {

        static boolean isController(JavaClass c) {
            return CONTROLLER_STEREOTYPES.stream().anyMatch(a -> has(c, a));
        }

        /** A bean source of the shape: scanned, not a controller, or a config-properties class. */
        static boolean isKept(JavaClass c) {
            return !isController(c)
                    && (isScannedComponent(c) || SpringWiring.isConfigurationPropertiesOnly(c));
        }
    }

    // ---- poms -------------------------------------------------------------------------------

    private static final Pattern DEPENDENCY =
            Pattern.compile(
                    "<dependency>\\s*<groupId>com\\.agenticai</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    /** The com.agenticai modules a pom depends on (from its own {@code <dependency>} blocks). */
    private static Set<String> coreDependencies(Path pom) throws Exception {
        File file = pom.toFile();
        assertThat(file).as("run from core/app (surefire's working directory)").exists();
        String xml = Files.readString(pom).replaceAll("(?s)<!--.*?-->", "");
        Matcher m = DEPENDENCY.matcher(xml);
        Set<String> found = new TreeSet<>();
        while (m.find()) {
            found.add(m.group(1).trim());
        }
        return found;
    }
}
