package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

/**
 * Two core replicas both run every {@code @Scheduled} method unless it takes the
 * Postgres advisory lock ({@code PgAdvisoryJobLock#tryRunExclusive}). That is how
 * {@code AgentExperimentService#sweepAutoStops} (and, found by this guard's first run,
 * {@code BriefTrustWatcher#poll} and {@code PaymentReconciliationJob#sweep}) shipped
 * unlocked: nothing failed, the second replica just did the work again.
 *
 * <p>Every {@code @Scheduled} method must therefore EITHER
 * <ul>
 *   <li>run inside {@code tryRunExclusive} (its class holds a {@code PgAdvisoryJobLock}
 *       field, and, when the source tree is readable, the method body names
 *       {@code tryRunExclusive}), OR</li>
 *   <li>carry {@link AllowUnlockedSchedule} (method or class) with a reason, for the narrow
 *       case where the job owns per-JVM state and every replica must run it.</li>
 * </ul>
 *
 * <p>Reflection answers "does the class know about the lock"; the source scan answers "does
 * THIS method use it" (a class with a lock field and two scheduled methods where only one is
 * wrapped would otherwise pass). The source scan is skipped, loudly in the assertion message
 * only when it finds something, if the module source folders are not reachable from the
 * working directory. Same shape as {@link InjectableConstructorTest}: classpath scan, no
 * Spring context, no database.
 *
 * <p>Honest limit: a method that delegates to a locked helper in ANOTHER class would be
 * flagged by the source scan; opt out with a reason pointing at the helper, or move the
 * {@code tryRunExclusive} call into the {@code @Scheduled} method itself (the project pattern).
 */
class ScheduledJobsAreLockedOrAnnotatedTest {

    private static final String LOCK_TYPE_NAME = "PgAdvisoryJobLock";
    private static final String LOCK_CALL = "tryRunExclusive";

    @Test
    void everyScheduledMethodIsAdvisoryLockedOrExplicitlyAllowedUnlocked() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                // Deliberately skips Spring's @Conditional evaluation: a job that is conditional in
                // this test environment must still be checked.
                var meta = metadataReader.getAnnotationMetadata();
                return meta.hasAnnotatedMethods(Scheduled.class.getName())
                    || meta.hasAnnotatedMethods(Schedules.class.getName());
            }

            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };

        Path sourceRoot = findModulesRoot();
        List<String> offenders = new ArrayList<>();
        int scheduledMethods = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.agenticai")) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> type = Class.forName(className, false, getClass().getClassLoader());
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Set<String> optedOutMethods = new HashSet<>();
            List<String> scheduledNames = new ArrayList<>();
            boolean classOptOut = type.isAnnotationPresent(AllowUnlockedSchedule.class);
            if (classOptOut) {
                requireReason(offenders, className, type.getAnnotation(AllowUnlockedSchedule.class));
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Scheduled.class) && !method.isAnnotationPresent(Schedules.class)) {
                    continue;
                }
                scheduledMethods++;
                scheduledNames.add(method.getName());
                AllowUnlockedSchedule allow = method.getAnnotation(AllowUnlockedSchedule.class);
                if (allow != null) {
                    requireReason(offenders, className + "#" + method.getName(), allow);
                    optedOutMethods.add(method.getName());
                }
            }
            if (classOptOut || scheduledNames.isEmpty()) {
                continue;
            }
            // Structural check: the class must hold the lock (direct field, ObjectProvider<..> or similar).
            boolean hasLockField = hasLockField(type);
            for (String name : scheduledNames) {
                if (!optedOutMethods.contains(name) && !hasLockField) {
                    offenders.add(className + "#" + name + " is @Scheduled but the class has no PgAdvisoryJobLock "
                        + "field and no @AllowUnlockedSchedule");
                }
            }
            // Per-method check against the source, when it is reachable.
            if (sourceRoot != null) {
                Path source = findSource(sourceRoot, className);
                if (source != null) {
                    String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    for (String name : unlockedScheduledMethods(text, optedOutMethods)) {
                        offenders.add(className + "#" + name + " is @Scheduled but its body never calls "
                            + LOCK_CALL + " and it is not @AllowUnlockedSchedule");
                    }
                }
            }
        }

        assertThat(scheduledMethods)
            .as("the scan must actually see the project's @Scheduled methods, or this guard is vacuous")
            .isGreaterThan(20);
        assertThat(offenders)
            .as("Every @Scheduled method runs on EVERY core replica. Wrap its body in "
                + "PgAdvisoryJobLock#tryRunExclusive(\"job-name\", ...) (see AgentSentinelSweep), or, if it only "
                + "touches per-JVM state, annotate it with @AllowUnlockedSchedule(reason = \"...\"). "
                + "Offenders")
            .isEmpty();
    }

    @Test
    void sourceScanFlagsAnUnlockedMethodAndAcceptsLockedAndOptedOutOnes() {
        String source = String.join("\n",
            "class Sample {",
            "    /** Javadoc mentioning\n     * @Scheduled} in prose must not count. */",
            "    @Scheduled(fixedDelay = 1000)",
            "    public void locked() {",
            "        jobLock.tryRunExclusive(\"x\", () -> work());",
            "    }",
            "",
            "    @Scheduled(cron = \"0 0 * * * *\", zone = \"UTC\")",
            "    @Transactional",
            "    public void unlocked() {",
            "        work();",
            "    }",
            "",
            "    @AllowUnlockedSchedule(reason = \"local\")",
            "    @Scheduled(fixedRate = 5)",
            "    void optedOut() {",
            "        work();",
            "    }",
            "}");
        assertThat(unlockedScheduledMethods(source, Set.of("optedOut"))).containsExactly("unlocked");
        assertThat(unlockedScheduledMethods(source, Set.of("optedOut", "unlocked"))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static void requireReason(List<String> offenders, String where, AllowUnlockedSchedule allow) {
        if (allow.reason() == null || allow.reason().isBlank()) {
            offenders.add(where + " has @AllowUnlockedSchedule with a blank reason");
        }
    }

    private static boolean hasLockField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getGenericType().getTypeName().contains(LOCK_TYPE_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Names of {@code @Scheduled} methods in {@code source} whose body does not call
     * {@code tryRunExclusive}, excluding {@code optedOut}. Plain brace matching: good enough for
     * the project's scheduled methods (balanced braces, none in string literals); the guard fails
     * loudly rather than silently if that ever stops holding.
     */
    static List<String> unlockedScheduledMethods(String source, Set<String> optedOut) {
        List<String> unlocked = new ArrayList<>();
        // Line-anchored, so "@Scheduled" inside a javadoc line ("* @Scheduled} ...") never matches.
        Matcher match = Pattern.compile("(?m)^[ \\t]*@Scheduled\\b").matcher(source);
        while (match.find()) {
            int afterAnnotation = match.end();
            if (afterAnnotation < source.length() && source.charAt(afterAnnotation) == '(') {
                int depth = 0;
                for (; afterAnnotation < source.length(); afterAnnotation++) {
                    char c = source.charAt(afterAnnotation);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
            }
            int open = source.indexOf('{', afterAnnotation);
            if (open < 0) {
                continue;
            }
            String signature = source.substring(Math.min(afterAnnotation + 1, open), open);
            String name = lastCallName(signature);
            int depth = 0;
            int end = open;
            for (; end < source.length(); end++) {
                char c = source.charAt(end);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            String body = source.substring(open, Math.min(end + 1, source.length()));
            if (name != null && !optedOut.contains(name) && !body.contains(LOCK_CALL)) {
                unlocked.add(name);
            }
        }
        return unlocked;
    }

    /** The identifier right before the last "(" of a method signature, e.g. {@code sweep} in {@code public void sweep() }. */
    private static String lastCallName(String signature) {
        Matcher m = Pattern.compile("(\\w+)\\s*\\(").matcher(signature);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** The directory that holds the module folders (core/), or null when the source tree is not reachable. */
    private static Path findModulesRoot() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (isModulesRoot(p)) {
                return p;
            }
            if (isModulesRoot(p.resolve("core"))) {
                return p.resolve("core");
            }
        }
        return null;
    }

    private static boolean isModulesRoot(Path dir) {
        return Files.isDirectory(dir.resolve("app").resolve("src")) && Files.isDirectory(dir.resolve("tenancy").resolve("src"));
    }

    private static Path findSource(Path modulesRoot, String className) throws IOException {
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        Path relative = Path.of("src", "main", "java").resolve(outer.replace('.', '/') + ".java");
        try (Stream<Path> modules = Files.list(modulesRoot)) {
            return modules
                .map(module -> module.resolve(relative))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        }
    }
}
