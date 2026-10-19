package com.agenticai.core.startup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;

/**
 * At readiness, turns the recording made by {@link StartupProfilingInitializer}
 * into something a human (or {@code scripts/ci/startup-profile.sh}) can read:
 * a handful of grep-able {@code startup-profile:} log lines (total per step
 * name, then the slowest individual steps by SELF time, i.e. excluding the
 * nested steps they triggered) and, when {@code app.startup.profiling.dump-file}
 * is set, the full timeline as JSON in the same shape as Spring Boot's
 * {@code /actuator/startup} (so one parser serves both). A dump file avoids any
 * HTTP or auth step for a local boot-check.
 *
 * <p>Does nothing unless the context's {@link ApplicationStartup} is the
 * {@link BufferingApplicationStartup} the initializer installed, so with the flag
 * off this listener is inert. Reports once. Never throws: profiling must not be
 * able to break readiness.
 *
 * <p>Note on "ready" timing: {@code ApplicationReadyEvent} is published after all
 * {@code ApplicationRunner}s (tenant migration included) and all synchronous
 * {@code @EventListener(ApplicationReadyEvent)} methods, so the reported
 * {@code ready in N ms} is the time to readiness, not the "Started
 * CoreApplication" figure (which is logged earlier, before the runners).
 */
public class StartupProfilingReporter implements ApplicationListener<ApplicationReadyEvent> {

    static final String TOP_PROPERTY = "app.startup.profiling.log-top";
    static final String DUMP_FILE_PROPERTY = "app.startup.profiling.dump-file";
    static final int DEFAULT_TOP = 25;
    private static final int BY_NAME_LINES = 12;
    private static final int MAX_TAG_CHARS = 160;

    private static final Log log = LogFactory.getLog(StartupProfilingReporter.class);

    private final AtomicBoolean reported = new AtomicBoolean(false);

    /** One recorded step flattened to what the summary needs. {@code parentId} is null for a root step. */
    record Step(long id, Long parentId, String name, String tags, long durationNanos) {
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ApplicationStartup startup = event.getApplicationContext().getApplicationStartup();
        if (!(startup instanceof BufferingApplicationStartup buffering)) {
            return;
        }
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        try {
            Environment environment = event.getApplicationContext().getEnvironment();
            int top = Math.max(1, environment.getProperty(TOP_PROPERTY, Integer.class, DEFAULT_TOP));
            int capacity = Math.max(StartupProfilingInitializer.MIN_CAPACITY,
                environment.getProperty(StartupProfilingInitializer.CAPACITY_PROPERTY, Integer.class,
                    StartupProfilingInitializer.DEFAULT_CAPACITY));
            // Non-draining read: the actuator endpoint (if exposed) still sees the same timeline.
            StartupTimeline timeline = buffering.getBufferedTimeline();
            List<Step> steps = toSteps(timeline.getEvents());
            for (String line : summarize(steps, top, event.getTimeTaken().toMillis(), capacity)) {
                log.info(line);
            }
            String dumpFile = environment.getProperty(DUMP_FILE_PROPERTY, "");
            if (dumpFile != null && !dumpFile.isBlank()) {
                writeDump(Path.of(dumpFile.trim()), timeline);
            }
        } catch (RuntimeException e) {
            log.warn("startup-profile: could not report the start-up timeline - " + e);
        }
    }

    // ---- pure helpers (package-private for unit tests) ------------------------------------------

    static List<Step> toSteps(List<StartupTimeline.TimelineEvent> events) {
        List<Step> steps = new ArrayList<>(events.size());
        for (StartupTimeline.TimelineEvent event : events) {
            StartupStep step = event.getStartupStep();
            StringBuilder tags = new StringBuilder();
            for (StartupStep.Tag tag : step.getTags()) {
                if (tags.length() > 0) {
                    tags.append(' ');
                }
                tags.append(tag.getKey()).append('=').append(tag.getValue());
            }
            String tagText = tags.length() > MAX_TAG_CHARS ? tags.substring(0, MAX_TAG_CHARS) + "..." : tags.toString();
            steps.add(new Step(step.getId(), step.getParentId(), step.getName(), tagText, event.getDuration().toNanos()));
        }
        return steps;
    }

    /**
     * Self time of a step = its own duration minus the durations of its direct children (the steps
     * recorded while it was the active step). Clamped at zero because clock granularity can make a
     * parent a hair shorter than the sum of its children.
     */
    static Map<Long, Long> selfNanosById(List<Step> steps) {
        Map<Long, Long> childSum = new HashMap<>();
        for (Step step : steps) {
            if (step.parentId() != null) {
                childSum.merge(step.parentId(), step.durationNanos(), Long::sum);
            }
        }
        Map<Long, Long> self = new HashMap<>();
        for (Step step : steps) {
            self.put(step.id(), Math.max(0L, step.durationNanos() - childSum.getOrDefault(step.id(), 0L)));
        }
        return self;
    }

    static List<String> summarize(List<Step> steps, int top, long readyMillis, int capacity) {
        List<String> lines = new ArrayList<>();
        lines.add("startup-profile: ready in " + readyMillis + " ms; " + steps.size() + " steps recorded (capacity "
            + capacity + ")");
        if (steps.size() >= capacity) {
            lines.add("startup-profile: WARNING the buffer is full, later steps were dropped - raise "
                + StartupProfilingInitializer.CAPACITY_PROPERTY);
        }
        Map<Long, Long> self = selfNanosById(steps);

        Map<String, long[]> byName = new HashMap<>(); // name -> {selfNanos, count}
        for (Step step : steps) {
            long[] acc = byName.computeIfAbsent(step.name(), k -> new long[2]);
            acc[0] += self.get(step.id());
            acc[1]++;
        }
        byName.entrySet().stream()
            .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong((long[] a) -> a[0]).reversed()))
            .limit(BY_NAME_LINES)
            .forEach(e -> lines.add("startup-profile: by-name self=" + ms(e.getValue()[0]) + " ms count="
                + e.getValue()[1] + " " + e.getKey()));

        List<Step> ranked = new ArrayList<>(steps);
        ranked.sort(Comparator.comparingLong((Step s) -> self.get(s.id())).reversed());
        int rank = 1;
        for (Step step : ranked.subList(0, Math.min(top, ranked.size()))) {
            lines.add("startup-profile: top#" + rank++ + " self=" + ms(self.get(step.id())) + " ms total="
                + ms(step.durationNanos()) + " ms " + step.name() + (step.tags().isEmpty() ? "" : " " + step.tags()));
        }
        return lines;
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    // ---- JSON dump, same shape as Spring Boot's /actuator/startup -------------------------------

    private static void writeDump(Path file, StartupTimeline timeline) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, toJson(timeline), StandardCharsets.UTF_8);
            log.info("startup-profile: wrote the full timeline to " + file);
        } catch (IOException | RuntimeException e) {
            log.warn("startup-profile: could not write " + file + " - " + e);
        }
    }

    static String toJson(StartupTimeline timeline) {
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("{\"springBootVersion\":");
        quote(out, SpringBootVersion.getVersion());
        out.append(",\"timeline\":{\"startTime\":");
        quote(out, timeline.getStartTime().toString());
        out.append(",\"events\":[");
        boolean first = true;
        for (StartupTimeline.TimelineEvent event : timeline.getEvents()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            StartupStep step = event.getStartupStep();
            out.append("{\"endTime\":");
            quote(out, event.getEndTime().toString());
            out.append(",\"duration\":");
            quote(out, event.getDuration().toString());
            out.append(",\"startTime\":");
            quote(out, event.getStartTime().toString());
            out.append(",\"startupStep\":{\"name\":");
            quote(out, step.getName());
            out.append(",\"id\":").append(step.getId());
            out.append(",\"tags\":[");
            boolean firstTag = true;
            for (StartupStep.Tag tag : step.getTags()) {
                if (!firstTag) {
                    out.append(',');
                }
                firstTag = false;
                out.append("{\"key\":");
                quote(out, tag.getKey());
                out.append(",\"value\":");
                quote(out, tag.getValue());
                out.append('}');
            }
            out.append(']');
            if (step.getParentId() != null) {
                out.append(",\"parentId\":").append(step.getParentId());
            }
            out.append("}}");
        }
        out.append("]}}");
        return out.toString();
    }

    private static void quote(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
