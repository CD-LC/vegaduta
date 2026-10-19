package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.metrics.StartupStep;

/**
 * Cold-start lane: start-up profiling is OFF by default (zero behaviour change), and when ON it ranks steps by
 * SELF time and can dump the same JSON shape as Spring Boot's {@code /actuator/startup}.
 */
class StartupProfilingTest {

    private static final long MS = 1_000_000L;

    // ---- initializer: off by default, on when asked --------------------------------------------------

    @Test
    void initializerLeavesSpringsDefaultNoOpStartupWhenTheFlagIsOff() {
        GenericApplicationContext context = new GenericApplicationContext();

        new StartupProfilingInitializer().initialize(context);

        assertThat(context.getApplicationStartup()).isNotInstanceOf(BufferingApplicationStartup.class);
    }

    @Test
    void initializerInstallsTheBufferingRecorderWhenTheFlagIsOn() {
        GenericApplicationContext context = new GenericApplicationContext();
        TestPropertyValues.of("app.startup.profiling.enabled=true").applyTo(context);

        new StartupProfilingInitializer().initialize(context);

        assertThat(context.getApplicationStartup()).isInstanceOf(BufferingApplicationStartup.class);
    }

    // ---- summary: self time, not total time ----------------------------------------------------------

    @Test
    void summaryRanksStepsBySelfTimeSoAParentDoesNotOutrankTheChildThatActuallyWasSlow() {
        // A (100 ms total) spent 90 ms inside its child B, so A's own cost is only 10 ms; C is a plain 50 ms step.
        List<StartupProfilingReporter.Step> steps = List.of(
            new StartupProfilingReporter.Step(1, null, "spring.beans.instantiate", "beanName=A", 100 * MS),
            new StartupProfilingReporter.Step(2, 1L, "spring.beans.instantiate", "beanName=B", 90 * MS),
            new StartupProfilingReporter.Step(3, null, "spring.beans.instantiate", "beanName=C", 50 * MS));

        List<String> lines = StartupProfilingReporter.summarize(steps, 3, 4321, 1000);

        assertThat(lines.get(0)).isEqualTo("startup-profile: ready in 4321 ms; 3 steps recorded (capacity 1000)");
        List<String> top = lines.stream().filter(l -> l.contains("top#")).toList();
        assertThat(top).hasSize(3);
        assertThat(top.get(0)).contains("top#1 self=90.0 ms total=90.0 ms").contains("beanName=B");
        assertThat(top.get(1)).contains("top#2 self=50.0 ms").contains("beanName=C");
        assertThat(top.get(2)).contains("top#3 self=10.0 ms total=100.0 ms").contains("beanName=A");
    }

    @Test
    void summaryAggregatesSelfTimeAndCountPerStepName() {
        List<StartupProfilingReporter.Step> steps = List.of(
            new StartupProfilingReporter.Step(1, null, "spring.beans.instantiate", "", 30 * MS),
            new StartupProfilingReporter.Step(2, null, "spring.beans.instantiate", "", 20 * MS),
            new StartupProfilingReporter.Step(3, null, "spring.context.component-scan", "", 5 * MS));

        List<String> lines = StartupProfilingReporter.summarize(steps, 1, 1, 1000);

        assertThat(lines).anyMatch(l -> l.equals("startup-profile: by-name self=50.0 ms count=2 spring.beans.instantiate"));
        assertThat(lines).anyMatch(l -> l.equals("startup-profile: by-name self=5.0 ms count=1 spring.context.component-scan"));
        assertThat(lines.stream().filter(l -> l.contains("top#"))).hasSize(1);
    }

    @Test
    void summaryWarnsWhenTheBufferFilledUpAndLaterStepsWereDropped() {
        List<StartupProfilingReporter.Step> steps = List.of(
            new StartupProfilingReporter.Step(1, null, "a", "", MS),
            new StartupProfilingReporter.Step(2, null, "b", "", MS));

        List<String> lines = StartupProfilingReporter.summarize(steps, 5, 1, 2);

        assertThat(lines).anyMatch(l -> l.contains("WARNING the buffer is full"));
    }

    @Test
    void aParentShorterThanItsChildrenNeverGoesNegative() {
        List<StartupProfilingReporter.Step> steps = List.of(
            new StartupProfilingReporter.Step(1, null, "parent", "", 10 * MS),
            new StartupProfilingReporter.Step(2, 1L, "child", "", 12 * MS));

        assertThat(StartupProfilingReporter.selfNanosById(steps).get(1L)).isZero();
    }

    // ---- real recorder: parent links, tags, JSON ------------------------------------------------------

    private static BufferingApplicationStartup recordedStartup() {
        BufferingApplicationStartup startup = new BufferingApplicationStartup(100);
        StartupStep parent = startup.start("spring.beans.instantiate");
        parent.tag("beanName", "parentBean");
        StartupStep child = startup.start("spring.beans.instantiate");
        child.tag("beanName", "quote\"and\nnewline");
        child.end();
        parent.end();
        return startup;
    }

    @Test
    void toStepsKeepsTheParentLinkAndTagsOfARealRecording() {
        List<StartupProfilingReporter.Step> steps = StartupProfilingReporter.toSteps(
            recordedStartup().getBufferedTimeline().getEvents());

        assertThat(steps).hasSize(2);
        StartupProfilingReporter.Step child = steps.stream().filter(s -> s.parentId() != null).findFirst().orElseThrow();
        StartupProfilingReporter.Step parent = steps.stream().filter(s -> s.parentId() == null).findFirst().orElseThrow();
        assertThat(child.parentId()).isEqualTo(parent.id());
        assertThat(parent.tags()).isEqualTo("beanName=parentBean");
    }

    @Test
    void jsonDumpIsValidJsonInTheActuatorStartupShapeAndEscapesTags() throws Exception {
        String json = StartupProfilingReporter.toJson(recordedStartup().getBufferedTimeline());

        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode events = root.path("timeline").path("events");
        assertThat(events.isArray()).isTrue();
        assertThat(events).hasSize(2);
        JsonNode first = events.get(0);
        assertThat(first.path("startupStep").path("name").asText()).isEqualTo("spring.beans.instantiate");
        assertThat(first.path("duration").asText()).startsWith("PT");
        assertThat(first.path("startupStep").path("tags").get(0).path("value").asText()).isEqualTo("quote\"and\nnewline");
        assertThat(first.path("startupStep").has("parentId")).isTrue();
    }

    // ---- reporter: inert unless profiling is on, writes the dump when asked --------------------------

    private static ApplicationReadyEvent readyEvent(GenericApplicationContext context) {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ofMillis(1500));
    }

    @Test
    void reporterDoesNothingAndNeverThrowsWhenProfilingIsOff() {
        GenericApplicationContext context = new GenericApplicationContext();
        TestPropertyValues.of("app.startup.profiling.dump-file=should-not-be-written.json").applyTo(context);

        new StartupProfilingReporter().onApplicationEvent(readyEvent(context));

        assertThat(Path.of("should-not-be-written.json")).doesNotExist();
    }

    @Test
    void reporterWritesTheTimelineDumpWhenAFileIsConfigured(@TempDir Path dir) throws Exception {
        Path dump = dir.resolve("nested").resolve("startup.json");
        GenericApplicationContext context = new GenericApplicationContext();
        TestPropertyValues.of(
            "app.startup.profiling.enabled=true",
            "app.startup.profiling.dump-file=" + dump.toString().replace('\\', '/')).applyTo(context);
        new StartupProfilingInitializer().initialize(context);
        context.getApplicationStartup().start("spring.beans.instantiate").end();

        new StartupProfilingReporter().onApplicationEvent(readyEvent(context));

        assertThat(dump).exists();
        JsonNode root = new ObjectMapper().readTree(Files.readString(dump, StandardCharsets.UTF_8));
        assertThat(root.path("timeline").path("events")).hasSize(1);
    }
}
