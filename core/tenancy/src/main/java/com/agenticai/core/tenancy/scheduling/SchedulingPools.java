package com.agenticai.core.tenancy.scheduling;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named pools of the jobs shape. A pool is a set of {@code @Scheduled} classes that one optional
 * Deployment owns; with that Deployment absent, the main jobs pod owns them, exactly as before.
 *
 * <p>Three properties, all empty by default (which changes nothing):
 *
 * <ul>
 *   <li>{@code app.scheduling.pools.<name>} - the classes pool {@code <name>} owns (defined once,
 *       in {@code application-shape-jobs.yml}).
 *   <li>{@code app.scheduling.offloaded-pools} - on the main jobs pod: pools that run elsewhere, so
 *       their classes are skipped here. Composes: any combination of pools can be offloaded, which
 *       a profile's replace-the-whole-list skip list cannot express.
 *   <li>{@code app.scheduling.pool} - on a pool pod: skip every product scheduled bean ({@code
 *       com.agenticai.core.*}) except the classes of this pool.
 *   <li>{@code app.scheduling.pool-lifecycle.<name>} - optional, per pool: per-JVM lifecycle
 *       classes (MCP, browser and sandbox sweeps) that also run in that pool's pod, because the
 *       pool's jobs run work that holds that state in the pod's own JVM (an agent turn). Empty for
 *       a pool whose jobs hold none.
 * </ul>
 *
 * <p>An unknown pool name fails the boot. Silently skipping nothing would run a job twice; silently
 * skipping everything would run it nowhere.
 */
public final class SchedulingPools {

    static final String POOLS = "app.scheduling.pools";
    static final String POOL = "app.scheduling.pool";
    static final String LIFECYCLE = "app.scheduling.pool-lifecycle";
    static final String OFFLOADED = "app.scheduling.offloaded-pools";
    static final String SKIP_BEAN_CLASSES = "app.scheduling.skip-bean-classes";
    static final String KEEP_BEAN_CLASSES = "app.scheduling.keep-bean-classes";
    static final String PRODUCT_PREFIX = "com.agenticai.core.*";

    private SchedulingPools() {}

    /** The matcher for {@code @Scheduled} beans: the skip list, plus pools, minus the keep list. */
    public static SchedulingRoleMatcher scheduledBeans(Binder binder) {
        Map<String, List<String>> pools = pools(binder);
        String pool = binder.bind(POOL, String.class).orElse("").trim();
        List<String> offloaded =
                names(binder.bind(OFFLOADED, Bindable.listOf(String.class)).orElse(List.of()));

        List<String> skip = new ArrayList<>(strings(binder, SKIP_BEAN_CLASSES));
        List<String> keep = new ArrayList<>(strings(binder, KEEP_BEAN_CLASSES));
        if (!pool.isEmpty()) {
            if (!offloaded.isEmpty()) {
                throw new IllegalStateException(
                        POOL
                                + "="
                                + pool
                                + " and "
                                + OFFLOADED
                                + "="
                                + offloaded
                                + " are exclusive");
            }
            skip.add(PRODUCT_PREFIX);
            keep.addAll(owned(pools, pool, POOL));
            keep.addAll(lifecycle(binder).getOrDefault(pool, List.of()));
        }
        for (String name : offloaded) {
            skip.addAll(owned(pools, name, OFFLOADED));
        }
        return SchedulingRoleMatcher.of(skip).except(SchedulingRoleMatcher.of(keep));
    }

    /** The classes of every defined pool, by name, in declaration order. */
    public static Map<String, List<String>> pools(Binder binder) {
        return classesByName(binder, POOLS);
    }

    /** The per-JVM lifecycle classes a pool's pod also runs, by pool name. */
    public static Map<String, List<String>> lifecycle(Binder binder) {
        return classesByName(binder, LIFECYCLE);
    }

    private static Map<String, List<String>> classesByName(Binder binder, String key) {
        ResolvableType type =
                ResolvableType.forClassWithGenerics(
                        Map.class,
                        ResolvableType.forClass(String.class),
                        ResolvableType.forClassWithGenerics(List.class, String.class));
        Map<String, List<String>> bound =
                binder.bind(key, Bindable.<Map<String, List<String>>>of(type)).orElse(Map.of());
        Map<String, List<String>> out = new LinkedHashMap<>();
        bound.forEach((name, classes) -> out.put(name, List.copyOf(classes)));
        return out;
    }

    private static List<String> owned(Map<String, List<String>> pools, String name, String key) {
        List<String> classes = pools.get(name);
        if (classes == null || classes.isEmpty()) {
            throw new IllegalStateException(
                    key + " names pool '" + name + "' but only " + pools.keySet() + " are defined");
        }
        return classes;
    }

    private static List<String> strings(Binder binder, String key) {
        return binder.bind(key, Bindable.listOf(String.class)).orElse(List.of());
    }

    private static List<String> names(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String entry : raw) {
            for (String part : entry.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}
