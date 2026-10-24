package com.agenticai.core.archrules;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;

/**
 * Structural rules that go beyond {@link ModuleBoundaryRulesTest}, introduced by the 2026-10-14
 * structural audit ({@code docs/architecture/STRUCTURAL-AUDIT-2026-10-14.md}).
 *
 * <p>Every rule is a {@link FreezingArchRule}: the violations that existed when the rule was
 * introduced are stored under {@code src/test/resources/archunit_store}, any new violation fails
 * the build, and fixed ones drop out of the store (it may only shrink). The maintenance steps are
 * the ones documented in {@link ModuleBoundaryRulesTest}.
 *
 * <p>A violation message must not contain a number that changes as code is fixed, otherwise
 * improving a class would register as a new violation. Messages here name only the class or
 * package.
 *
 * <p>"Feature area" below means the first package segment under {@code com.agenticai.core} ({@code
 * agents}, {@code tenancy}, {@code platform}, ...). It matches the Maven module except for the
 * vendor adapters that are still compiled inside {@code agents} (audit finding F1).
 */
class StructureRulesTest {

    private static final String ROOT = ModuleBoundaryRulesTest.ROOT;

    /**
     * A constructor with more collaborator parameters than this is a class doing too much. Settings
     * injected with {@code @Value} are configuration, not collaborators, and do not count.
     */
    static final int MAX_CONSTRUCTOR_PARAMETERS = 12;

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

    // ---------------------------------------------------------------------------------------
    // Rule 1: no package participates in a dependency cycle, at any depth.
    // ---------------------------------------------------------------------------------------

    /**
     * ArchUnit's {@code beFreeOfCycles()} lists individual cycles, capped at a configured number.
     * With a 44-package cycle group the number of cycles is enormous, the listed subset can change
     * from one build to the next, and a frozen baseline built from it would fail at random. This
     * condition reports each package that belongs to a strongly connected group of packages
     * instead. That set is exact and stable, and the baseline can only shrink.
     *
     * <p>It does not notice a new edge between two packages that are already in the same group.
     * Moving a package out of a group, or into one, is detected.
     */
    static final ArchRule NO_PACKAGE_IS_IN_A_CYCLE =
            classes()
                    .that()
                    .resideInAPackage(ROOT + "..")
                    .should(bePackageRepresentativeOutsideEveryCycle())
                    .as("no package may be part of a package dependency cycle (audit F2)")
                    .because(
                            "cycles between packages make a module impossible to split or test in "
                                    + "isolation; ModuleBoundaryRulesTest only checks top-level packages");

    @Test
    void noPackageIsPartOfADependencyCycle() {
        FreezingArchRule.freeze(NO_PACKAGE_IS_IN_A_CYCLE).check(classes);
    }

    private static ArchCondition<JavaClass> bePackageRepresentativeOutsideEveryCycle() {
        return new ArchCondition<JavaClass>("not belong to a package that is part of a cycle") {
            private Set<String> packagesInCycles = Set.of();
            private final Set<String> reported = new HashSet<>();

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                reported.clear();
                Map<String, Set<String>> graph = new HashMap<>();
                for (JavaClass c : allObjectsToTest) {
                    String from = c.getPackageName();
                    graph.computeIfAbsent(from, k -> new HashSet<>());
                    for (Dependency d : c.getDirectDependenciesFromSelf()) {
                        String to = d.getTargetClass().getPackageName();
                        if (to.startsWith(ROOT) && !to.equals(from)) {
                            graph.get(from).add(to);
                        }
                    }
                }
                packagesInCycles = packagesInStronglyConnectedGroups(graph);
            }

            @Override
            public void check(JavaClass c, ConditionEvents events) {
                String pkg = c.getPackageName();
                // One event per package, not per class, so the baseline stays small.
                if (packagesInCycles.contains(pkg) && reported.add(pkg)) {
                    events.add(
                            violated(
                                    c,
                                    "Package " + pkg + " is part of a package dependency cycle"));
                }
            }
        };
    }

    /** Iterative Tarjan: the packages that sit in a strongly connected group of two or more. */
    static Set<String> packagesInStronglyConnectedGroups(Map<String, Set<String>> graph) {
        Set<String> nodes = new TreeSet<>(graph.keySet());
        graph.values().forEach(nodes::addAll);
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> result = new LinkedHashSet<>();
        int[] counter = {0};

        for (String root : nodes) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[] {root, graph.getOrDefault(root, Set.of()).iterator()});
            index.put(root, counter[0]);
            low.put(root, counter[0]);
            counter[0]++;
            stack.push(root);
            onStack.add(root);
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                @SuppressWarnings("unchecked")
                java.util.Iterator<String> it = (java.util.Iterator<String>) frame[1];
                if (it.hasNext()) {
                    String w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]);
                        low.put(w, counter[0]);
                        counter[0]++;
                        stack.push(w);
                        onStack.add(w);
                        work.push(new Object[] {w, graph.getOrDefault(w, Set.of()).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        String parent = (String) work.peek()[0];
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                    if (low.get(v).equals(index.get(v))) {
                        List<String> component = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            component.add(w);
                        } while (!w.equals(v));
                        if (component.size() > 1) {
                            result.addAll(component);
                        }
                    }
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------
    // Rule 2: controllers talk to services, not to repositories or JPA entities.
    // ---------------------------------------------------------------------------------------

    static final DescribedPredicate<JavaClass> A_REPOSITORY =
            DescribedPredicate.describe(
                    "a Spring Data repository", c -> c.isAssignableTo(Repository.class));

    static final DescribedPredicate<JavaClass> A_JPA_ENTITY =
            DescribedPredicate.describe(
                    "a JPA entity",
                    c ->
                            c.isAnnotatedWith("jakarta.persistence.Entity")
                                    || c.isAnnotatedWith("jakarta.persistence.MappedSuperclass"));

    static final ArchRule CONTROLLERS_DO_NOT_TOUCH_PERSISTENCE =
            classes()
                    .that()
                    .areMetaAnnotatedWith(Controller.class)
                    .should(notDependOnDistinct(A_REPOSITORY, "repository"))
                    .andShould(notDependOnDistinct(A_JPA_ENTITY, "JPA entity"))
                    .as("a controller must not depend on a repository or a JPA entity (audit F5)")
                    .because(
                            "web code returns DTOs produced by a service; reaching into persistence "
                                    + "from a controller bypasses transactions, tenancy and authorization");

    @Test
    void controllersDoNotDependOnRepositoriesOrEntities() {
        FreezingArchRule.freeze(CONTROLLERS_DO_NOT_TOUCH_PERSISTENCE).check(classes);
    }

    // ---------------------------------------------------------------------------------------
    // Rule 3: a feature area does not reach into another area's repositories.
    // ---------------------------------------------------------------------------------------

    static final ArchRule NO_CROSS_AREA_REPOSITORY_ACCESS =
            classes()
                    .that()
                    .resideInAPackage(ROOT + ".(*)..")
                    .should(notDependOnRepositoryOfAnotherArea())
                    .as("a feature area must not use another area's repository (audit F4)")
                    .because(
                            "an area's tables are private; other areas go through its service "
                                    + "interface so the schema can change and the area can be split off");

    @Test
    void noFeatureAreaUsesAnotherAreasRepository() {
        FreezingArchRule.freeze(NO_CROSS_AREA_REPOSITORY_ACCESS).check(classes);
    }

    private static ArchCondition<JavaClass> notDependOnRepositoryOfAnotherArea() {
        return new ArchCondition<JavaClass>("not depend on a repository of another feature area") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                String area = featureArea(c);
                if (area == null) {
                    return;
                }
                Set<String> seen = new TreeSet<>();
                for (Dependency d : c.getDirectDependenciesFromSelf()) {
                    JavaClass target = d.getTargetClass();
                    if (A_REPOSITORY.test(target)
                            && !target.getName().startsWith("org.springframework")
                            && featureArea(target) != null
                            && !area.equals(featureArea(target))) {
                        seen.add(target.getName());
                    }
                }
                for (String target : seen) {
                    events.add(
                            violated(
                                    c,
                                    c.getName()
                                            + " (area "
                                            + area
                                            + ") depends on repository "
                                            + target));
                }
            }
        };
    }

    /** First package segment below {@code com.agenticai.core}; null for the root package itself. */
    static String featureArea(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith(ROOT + ".")) {
            return null;
        }
        String rest = pkg.substring(ROOT.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    // ---------------------------------------------------------------------------------------
    // Rule 4: bounded collaborators.
    // ---------------------------------------------------------------------------------------

    static final ArchRule CONSTRUCTORS_HAVE_BOUNDED_PARAMETERS =
            classes()
                    .that()
                    .resideInAPackage(ROOT + "..")
                    .and()
                    .areNotRecords()
                    .and()
                    .areNotEnums()
                    .should(haveConstructorsWithAtMost(MAX_CONSTRUCTOR_PARAMETERS))
                    .as(
                            "a class must not have a constructor with more than "
                                    + MAX_CONSTRUCTOR_PARAMETERS
                                    + " collaborator parameters (audit F3; @Value settings do not"
                                    + " count)")
                    .because(
                            "a constructor that needs that many collaborators means the class has "
                                    + "several responsibilities; split it by responsibility");

    @Test
    void constructorsHaveBoundedParameters() {
        FreezingArchRule.freeze(CONSTRUCTORS_HAVE_BOUNDED_PARAMETERS).check(classes);
    }

    private static ArchCondition<JavaClass> haveConstructorsWithAtMost(int max) {
        return new ArchCondition<JavaClass>(
                "have constructors with at most " + max + " collaborator parameters") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                int most = 0;
                for (JavaConstructor ctor : c.getConstructors()) {
                    int collaborators = 0;
                    for (JavaParameter parameter : ctor.getParameters()) {
                        if (!parameter.isAnnotatedWith(Value.class)) {
                            collaborators++;
                        }
                    }
                    most = Math.max(most, collaborators);
                }
                if (most > max) {
                    events.add(
                            violated(
                                    c,
                                    c.getName()
                                            + " has a constructor with more than "
                                            + max
                                            + " collaborator parameters"));
                }
            }
        };
    }

    // ---------------------------------------------------------------------------------------

    private static ArchCondition<JavaClass> notDependOnDistinct(
            DescribedPredicate<JavaClass> target, String what) {
        return new ArchCondition<JavaClass>("not depend on " + what) {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                Set<String> seen = new TreeSet<>();
                for (Dependency d : c.getDirectDependenciesFromSelf()) {
                    JavaClass t = d.getTargetClass();
                    if (target.test(t) && !t.getName().startsWith("org.springframework")) {
                        seen.add(t.getName());
                    }
                }
                for (String t : seen) {
                    events.add(violated(c, c.getName() + " depends on " + what + " " + t));
                }
            }
        };
    }
}
