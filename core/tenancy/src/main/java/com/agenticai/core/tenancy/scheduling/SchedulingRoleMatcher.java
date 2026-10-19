package com.agenticai.core.tenancy.scheduling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Decides whether a class is named by one of the deployment-shape skip lists ({@code
 * app.scheduling.skip-bean-classes} / {@code app.scheduling.skip-listener-classes}).
 *
 * <p>A pattern is either an exact fully-qualified class name, or a prefix ending in {@code *}
 * ({@code com.agenticai.core.omnichannel.*} names every class under that package and its
 * sub-packages). Blank entries are ignored. An EMPTY matcher matches nothing, which is the default
 * and means every scheduled bean and every startup listener runs exactly as it did before this
 * class existed.
 *
 * <p>{@link #except} carves exceptions out of a broad pattern, which is how a narrow pool says
 * "skip every scheduled bean except these": {@code skip com.agenticai.core.*}, {@code keep
 * com.agenticai.core.knowledge.KnowledgeIngestionJobRunner}.
 */
public final class SchedulingRoleMatcher {

    private final List<String> exact;
    private final List<String> prefixes;
    private final SchedulingRoleMatcher keep;

    private SchedulingRoleMatcher(
            List<String> exact, List<String> prefixes, SchedulingRoleMatcher keep) {
        this.exact = exact;
        this.prefixes = prefixes;
        this.keep = keep;
    }

    /** Builds a matcher from raw patterns (each element may itself be comma-separated). */
    public static SchedulingRoleMatcher of(Collection<String> patterns) {
        List<String> exact = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        if (patterns != null) {
            for (String raw : patterns) {
                if (raw == null) {
                    continue;
                }
                for (String part : raw.split(",")) {
                    String pattern = part.trim();
                    if (pattern.isEmpty()) {
                        continue;
                    }
                    if (pattern.endsWith("*")) {
                        String prefix = pattern.substring(0, pattern.length() - 1);
                        if (!prefix.isEmpty()) {
                            prefixes.add(prefix);
                        }
                    } else {
                        exact.add(pattern);
                    }
                }
            }
        }
        return new SchedulingRoleMatcher(List.copyOf(exact), List.copyOf(prefixes), null);
    }

    /**
     * Reads {@code key} through the {@link Binder}, so a comma-separated string, a YAML list and a
     * relaxed environment variable ({@code APP_SCHEDULING_SKIP_BEAN_CLASSES}) all work.
     */
    public static SchedulingRoleMatcher fromEnvironment(Environment environment, String key) {
        List<String> patterns =
                Binder.get(environment).bind(key, Bindable.listOf(String.class)).orElse(List.of());
        return of(patterns);
    }

    /**
     * A matcher that matches what this one matches, except the classes {@code keep} names. An empty
     * {@code keep} returns this matcher unchanged, so a deployment that never sets a keep list
     * behaves exactly as before.
     */
    public SchedulingRoleMatcher except(SchedulingRoleMatcher keep) {
        if (keep == null || keep.isEmpty()) {
            return this;
        }
        return new SchedulingRoleMatcher(exact, prefixes, keep);
    }

    public boolean isEmpty() {
        return exact.isEmpty() && prefixes.isEmpty();
    }

    public boolean matches(Class<?> type) {
        return type != null && matches(type.getName());
    }

    public boolean matches(String className) {
        if (className == null || isEmpty()) {
            return false;
        }
        // A CGLIB subclass is named "<target>$$SpringCGLIB$$0"; the operator
        // writes the target's name.
        int proxy = className.indexOf("$$");
        String name = proxy > 0 ? className.substring(0, proxy) : className;
        if (keep != null && keep.matches(name)) {
            return false;
        }
        if (exact.contains(name)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
