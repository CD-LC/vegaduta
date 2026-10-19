package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.tenancy.scheduling.SchedulingPools;
import com.agenticai.core.tenancy.scheduling.SchedulingRoleMatcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The jobs shape can run as one pod family ({@code shape-jobs}) or as two pools ({@code
 * shape-jobs-core} plus {@code shape-jobs-ingest}). A pool split moves a poller to another pod; it
 * must never make one stop running. A typo in a skip list is silent (an unknown class name skips
 * nothing), so this test reads the real profile files and the real {@code @Scheduled} classes and
 * checks, for the configuration an operator would deploy:
 *
 * <ul>
 *   <li>every class named in a profile exists and really has a {@code @Scheduled} (or
 *       {@code @EventListener}) method, so no entry is dead;
 *   <li>everything the single jobs pod runs is run by at least one of the two pools;
 *   <li>the heavy knowledge pollers run in the ingest pool and nowhere else in the jobs family;
 *   <li>the ingest pool skips every boot-time sweep that another shape owns.
 * </ul>
 */
class JobsPoolsCoverEveryJobTest {

    private static final String SKIP_BEANS = "app.scheduling.skip-bean-classes";
    private static final String KEEP_BEANS = "app.scheduling.keep-bean-classes";
    private static final String SKIP_LISTENERS = "app.scheduling.skip-listener-classes";

    private static final Set<String> KNOWLEDGE_POLLERS =
            Set.of(
                    "com.agenticai.core.knowledge.KnowledgeIngestionJobRunner",
                    "com.agenticai.core.knowledge.KnowledgeReembedJobRunner");

    @Test
    void everyClassNamedInAProfileExistsAndHasWhatTheListSkips() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String profile : List.of("api", "jobs", "jobs-core", "jobs-ingest", "jobs-pool")) {
            Properties props = load(profile);
            for (String name : list(props, SKIP_BEANS, KEEP_BEANS)) {
                if (name.endsWith("*")) {
                    continue; // a package prefix, not a class
                }
                Class<?> type = classOrNull(name);
                if (type == null) {
                    problems.add(profile + ": " + name + " does not exist");
                } else if (!hasScheduled(type)) {
                    problems.add(profile + ": " + name + " has no @Scheduled method");
                }
            }
            for (String name : list(props, SKIP_LISTENERS)) {
                Class<?> type = classOrNull(name);
                if (type == null) {
                    problems.add(profile + ": listener " + name + " does not exist");
                } else if (!hasEventListener(type)) {
                    problems.add(profile + ": listener " + name + " has no @EventListener method");
                }
            }
        }
        assertThat(problems)
                .as(
                        "a skip-list entry that matches nothing silently skips nothing; fix the"
                                + " profile file")
                .isEmpty();
    }

    @Test
    void theTwoPoolsTogetherRunEverythingTheSingleJobsPodRuns() throws Exception {
        SchedulingRoleMatcher single = skip(load("jobs"));
        SchedulingRoleMatcher core = skip(load("jobs-core"));
        SchedulingRoleMatcher ingest = skip(load("jobs-ingest"));

        List<Class<?>> scheduled = scheduledClasses();
        assertThat(scheduled)
                .as("the scan must see the project's @Scheduled classes, or this guard is vacuous")
                .hasSizeGreaterThan(30);

        List<String> dropped = new ArrayList<>();
        for (Class<?> type : scheduled) {
            boolean runsSingle = !single.matches(type);
            boolean runsCore = !core.matches(type);
            boolean runsIngest = !ingest.matches(type);
            if (runsSingle && !runsCore && !runsIngest) {
                dropped.add(type.getName());
            }
        }
        assertThat(dropped)
                .as(
                        "These @Scheduled classes run in the single jobs pod but in NEITHER pool of"
                            + " a split, so splitting would silently stop them. Add them to"
                            + " shape-jobs-core's world (do not skip them there) or keep them in"
                            + " shape-jobs-ingest")
                .isEmpty();
    }

    @Test
    void theKnowledgePollersRunInTheIngestPoolAndNowhereElseInTheJobsFamily() throws Exception {
        SchedulingRoleMatcher api = skip(load("api"));
        SchedulingRoleMatcher core = skip(load("jobs-core"));
        SchedulingRoleMatcher ingest = skip(load("jobs-ingest"));
        for (String name : KNOWLEDGE_POLLERS) {
            assertThat(ingest.matches(name)).as(name + " must run in the ingest pool").isFalse();
            assertThat(core.matches(name)).as(name + " must NOT run in the core pool").isTrue();
            assertThat(api.matches(name)).as(name + " must not run on the chat pods").isTrue();
        }
    }

    @Test
    void theIngestPoolRunsNothingButTheKnowledgePollers() throws Exception {
        SchedulingRoleMatcher ingest = skip(load("jobs-ingest"));
        List<String> unexpected = new ArrayList<>();
        for (Class<?> type : scheduledClasses()) {
            if (!ingest.matches(type) && !KNOWLEDGE_POLLERS.contains(type.getName())) {
                unexpected.add(type.getName());
            }
        }
        assertThat(unexpected)
                .as("the ingest pool must stay narrow: these product pollers would tick there too")
                .isEmpty();
    }

    @Test
    void theCorePoolKeepsShapeJobsListenersAndTheIngestPoolSkipsEveryOtherShapesBootSweep()
            throws Exception {
        List<String> jobsListeners = list(load("jobs"), SKIP_LISTENERS);
        List<String> apiListeners = list(load("api"), SKIP_LISTENERS);
        assertThat(list(load("jobs-core"), SKIP_LISTENERS))
                .as("the core pool must skip exactly what the single jobs pod skips")
                .containsExactlyInAnyOrderElementsOf(jobsListeners);

        Set<String> expected = new TreeSet<>(jobsListeners);
        expected.addAll(apiListeners);
        assertThat(list(load("jobs-ingest"), SKIP_LISTENERS))
                .as(
                        "the ingest pool owns no boot-time sweep, so it skips the api shape's and"
                                + " the jobs shape's")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ------------------------------------------------- optional named pools (any combination)

    @Test
    void everyPoolOwnsRealScheduledClassesAndNoClassIsInTwoPools() throws Exception {
        Map<String, List<String>> pools = SchedulingPools.pools(binder(load("jobs")));
        assertThat(pools.keySet()).containsExactlyInAnyOrder(POOLS.toArray(String[]::new));
        Set<String> seen = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        pools.forEach(
                (name, classes) -> {
                    assertThat(classes).as("pool " + name).isNotEmpty();
                    for (String cls : classes) {
                        Class<?> type = classOrNull(cls);
                        if (type == null || !hasScheduled(type)) {
                            problems.add(name + ": " + cls + " is missing or has no @Scheduled");
                        }
                        if (!seen.add(cls)) {
                            problems.add(cls + " is in two pools");
                        }
                    }
                });
        assertThat(problems).isEmpty();
    }

    @Test
    void everyLifecycleEntryNamesARealPoolAndARealScheduledClassThatNoPoolOwns() throws Exception {
        Binder jobs = binder(load("jobs"));
        Map<String, List<String>> pools = SchedulingPools.pools(jobs);
        Set<String> owned = new TreeSet<>();
        pools.values().forEach(owned::addAll);
        List<String> problems = new ArrayList<>();
        SchedulingPools.lifecycle(jobs)
                .forEach(
                        (name, classes) -> {
                            if (!pools.containsKey(name)) {
                                problems.add("pool-lifecycle." + name + " is not a pool");
                            }
                            for (String cls : classes) {
                                Class<?> type = classOrNull(cls);
                                if (type == null || !hasScheduled(type)) {
                                    problems.add(cls + " is missing or has no @Scheduled");
                                }
                                if (owned.contains(cls)) {
                                    problems.add(cls + " is owned by a pool AND a lifecycle entry");
                                }
                            }
                        });
        assertThat(problems).isEmpty();
    }

    @Test
    void theIntakePoolKeepsEveryPerJvmLifecycleClassTheAgentTurnsItRunsNeed() throws Exception {
        // The intake pollers call agentChatService.chat, which opens MCP connections, browser
        // sessions and sandbox artifacts in the pod's own JVM; only these sweeps reap them.
        List<String> mustTick =
                List.of(
                        "com.agenticai.core.agents.tools.DynamicMcpConnectionManager",
                        "com.agenticai.core.agents.tools.mcpregistry.RedisMcpOwnershipGate",
                        "com.agenticai.core.agents.tools.mcpregistry.McpOrphanAdopter",
                        "com.agenticai.core.agents.tools.browseruse.BrowserUseSessionManager",
                        "com.agenticai.core.agents.tools.browseruse.BrowserTakeoverService",
                        "com.agenticai.core.agents.tools.e2b.SandboxArtifactRetention",
                        "com.agenticai.core.platform.LandingEventBuffer");
        SchedulingRoleMatcher intake =
                SchedulingPools.scheduledBeans(
                        binder(merged("jobs", "jobs-pool"), "app.scheduling.pool", "intake"));
        List<String> notRunning = new ArrayList<>();
        for (String cls : mustTick) {
            if (intake.matches(cls)) {
                notRunning.add(cls);
            }
        }
        assertThat(notRunning).isEmpty();
        for (String pool : List.of("webhooks", "billing", "ingest")) {
            SchedulingRoleMatcher other =
                    SchedulingPools.scheduledBeans(
                            binder(merged("jobs", "jobs-pool"), "app.scheduling.pool", pool));
            for (String cls : mustTick) {
                assertThat(other.matches(cls)).as(pool + " must not tick " + cls).isTrue();
            }
        }
    }

    @Test
    void forEveryCombinationOfPoolsEveryJobRunsInExactlyOnePod() throws Exception {
        List<Class<?>> scheduled = scheduledClasses();
        Map<String, List<String>> pools = SchedulingPools.pools(binder(load("jobs")));
        Set<String> pooled = new TreeSet<>();
        pools.values().forEach(pooled::addAll);
        SchedulingRoleMatcher single = skip(load("jobs"));
        Map<String, List<String>> lifecycle = SchedulingPools.lifecycle(binder(load("jobs")));

        List<String> problems = new ArrayList<>();
        for (int mask = 0; mask < (1 << POOLS.size()); mask++) {
            List<String> enabled = new ArrayList<>();
            for (int i = 0; i < POOLS.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    enabled.add(POOLS.get(i));
                }
            }
            SchedulingRoleMatcher main =
                    SchedulingPools.scheduledBeans(
                            binder(
                                    merged("jobs"),
                                    "app.scheduling.offloaded-pools",
                                    String.join(",", enabled)));
            Map<String, SchedulingRoleMatcher> podsByPool = new java.util.LinkedHashMap<>();
            for (String name : enabled) {
                podsByPool.put(
                        name,
                        SchedulingPools.scheduledBeans(
                                binder(merged("jobs", "jobs-pool"), "app.scheduling.pool", name)));
            }
            for (Class<?> type : scheduled) {
                if (single.matches(type)) {
                    continue; // not a jobs-family job (the api shape owns it)
                }
                List<String> runsIn = new ArrayList<>();
                if (!main.matches(type)) {
                    runsIn.add("main");
                }
                podsByPool.forEach(
                        (name, pod) -> {
                            if (!pod.matches(type)) {
                                runsIn.add(name);
                            }
                        });
                String owner = ownerOf(pools, type.getName());
                List<String> expected = new ArrayList<>();
                if (owner != null && enabled.contains(owner)) {
                    expected.add(owner);
                } else {
                    expected.add("main");
                }
                // A per-JVM lifecycle class also runs in the pods of the pools that list it.
                for (String name : enabled) {
                    if (lifecycle.getOrDefault(name, List.of()).contains(type.getName())) {
                        expected.add(name);
                    }
                }
                if (!runsIn.equals(expected)) {
                    problems.add(
                            enabled
                                    + ": "
                                    + type.getName()
                                    + " runs in "
                                    + runsIn
                                    + ", want "
                                    + expected);
                }
            }
        }
        assertThat(problems)
                .as(
                        "with any pools enabled, each job the single jobs pod runs must run in"
                                + " exactly one pod (plus the pods of pools that list it as a"
                                + " per-JVM lifecycle class)")
                .isEmpty();
    }

    @Test
    void offloadingTheIngestPoolIsTheLegacyCorePoolAndThePoolPodIsTheLegacyIngestPod()
            throws Exception {
        SchedulingRoleMatcher legacyCore = skip(load("jobs-core"));
        SchedulingRoleMatcher offloaded =
                SchedulingPools.scheduledBeans(
                        binder(merged("jobs"), "app.scheduling.offloaded-pools", "ingest"));
        SchedulingRoleMatcher legacyIngest = skip(load("jobs-ingest"));
        SchedulingRoleMatcher pool =
                SchedulingPools.scheduledBeans(
                        binder(merged("jobs", "jobs-pool"), "app.scheduling.pool", "ingest"));
        List<String> differ = new ArrayList<>();
        for (Class<?> type : scheduledClasses()) {
            if (legacyCore.matches(type) != offloaded.matches(type)) {
                differ.add("core " + type.getName());
            }
            if (legacyIngest.matches(type) != pool.matches(type)) {
                differ.add("ingest " + type.getName());
            }
        }
        assertThat(differ).isEmpty();
    }

    @Test
    void aPoolPodSkipsEveryBootSweepAnotherShapeOwns() throws Exception {
        assertThat(list(load("jobs-pool"), SKIP_LISTENERS))
                .containsExactlyInAnyOrderElementsOf(list(load("jobs-ingest"), SKIP_LISTENERS));
    }

    @Test
    void anUnknownPoolNameFailsTheBootInsteadOfDroppingOrDoublingJobs() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () ->
                        SchedulingPools.scheduledBeans(
                                binder(merged("jobs"), "app.scheduling.pool", "webhook")));
        assertThrows(
                IllegalStateException.class,
                () ->
                        SchedulingPools.scheduledBeans(
                                binder(
                                        merged("jobs"),
                                        "app.scheduling.offloaded-pools",
                                        "ingest,x")));
    }

    @Test
    void noPoolSettingChangesNothing() throws Exception {
        SchedulingRoleMatcher plain = skip(load("jobs"));
        SchedulingRoleMatcher viaPools = SchedulingPools.scheduledBeans(binder(merged("jobs")));
        for (Class<?> type : scheduledClasses()) {
            assertThat(viaPools.matches(type)).as(type.getName()).isEqualTo(plain.matches(type));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> POOLS = List.of("ingest", "webhooks", "intake", "billing");

    private static String ownerOf(Map<String, List<String>> pools, String className) {
        for (Map.Entry<String, List<String>> e : pools.entrySet()) {
            if (e.getValue().contains(className)) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Profiles applied in order; a later profile's list replaces an earlier one's, as in Spring.
     */
    private static Properties merged(String... shapes) throws IOException {
        Properties out = new Properties();
        for (String shape : shapes) {
            Properties next = load(shape);
            Set<String> bases = new LinkedHashSet<>();
            for (String key : next.stringPropertyNames()) {
                int idx = key.indexOf('[');
                bases.add(idx < 0 ? key : key.substring(0, idx));
            }
            for (String base : bases) {
                out.stringPropertyNames().stream()
                        .filter(k -> k.startsWith(base + "["))
                        .forEach(out::remove);
            }
            out.putAll(next);
        }
        return out;
    }

    private static Binder binder(Properties props, String... overrides) {
        Properties all = new Properties();
        all.putAll(props);
        for (int i = 0; i < overrides.length; i += 2) {
            all.setProperty(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(all));
    }

    private static SchedulingRoleMatcher skip(Properties props) {
        return SchedulingRoleMatcher.of(list(props, SKIP_BEANS))
                .except(SchedulingRoleMatcher.of(list(props, KEEP_BEANS)));
    }

    private static Properties load(String shape) throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        ClassPathResource resource = new ClassPathResource("application-shape-" + shape + ".yml");
        assertThat(resource.exists()).as(resource.getPath() + " must be on the classpath").isTrue();
        yaml.setResources(resource);
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        return props;
    }

    private static List<String> list(Properties props, String... keys) {
        List<String> out = new ArrayList<>();
        Binder binder = new Binder(new MapConfigurationPropertySource(props));
        for (String key : keys) {
            binder.bind(key, Bindable.listOf(String.class)).ifBound(out::addAll);
        }
        return out;
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name, false, JobsPoolsCoverEveryJobTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static boolean hasScheduled(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Scheduled.class) || m.isAnnotationPresent(Schedules.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEventListener(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(EventListener.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> scheduledClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(MetadataReader reader) {
                        var meta = reader.getAnnotationMetadata();
                        return meta.hasAnnotatedMethods(Scheduled.class.getName())
                                || meta.hasAnnotatedMethods(Schedules.class.getName());
                    }

                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return true;
                    }
                };
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.agenticai")) {
            Class<?> type =
                    Class.forName(
                            bd.getBeanClassName(),
                            false,
                            JobsPoolsCoverEveryJobTest.class.getClassLoader());
            if (!type.isInterface()
                    && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                out.add(type);
            }
        }
        return out;
    }
}
