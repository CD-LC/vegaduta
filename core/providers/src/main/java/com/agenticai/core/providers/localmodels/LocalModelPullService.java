package com.agenticai.core.providers.localmodels;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Background {@code ollama pull} jobs for {@code POST /api/local-models/pull}. A pull takes
 * minutes, so the request returns a job id at once and the console polls {@code GET
 * /api/local-models/pull/{jobId}}.
 *
 * <ul>
 *   <li><b>One pull per tag.</b> Asking for a tag that is already downloading returns that job.
 *   <li><b>Bounded.</b> At most {@link #MAX_ACTIVE} jobs are pending or running; more is refused
 *       ({@link TooManyPullsException}). Finished jobs are kept for an hour (at most 100) so a late
 *       poll still gets its answer, then dropped.
 *   <li><b>Progress</b> is the sum of completed over the sum of total bytes of the layers Ollama
 *       has announced so far; it is capped at 99 until Ollama reports {@code success}, because a
 *       layer list is only complete at the end.
 *   <li><b>Not tenant data.</b> The model store belongs to the Ollama server, not to a tenant: jobs
 *       are platform-level, run with no tenant context, and a job id is an unguessable UUID.
 * </ul>
 */
@Component
public class LocalModelPullService {

    private static final Logger log = LoggerFactory.getLogger(LocalModelPullService.class);

    static final int MAX_ACTIVE = 2;
    static final int MAX_RETAINED = 100;
    static final Duration RETAIN_FINISHED = Duration.ofHours(1);

    /** Thrown when {@link #MAX_ACTIVE} pulls are already in flight. */
    public static final class TooManyPullsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooManyPullsException() {
            super("Too many model downloads are running. Wait for one to finish.");
        }
    }

    /** The API's pull status: {@code status} is pending, running, done or failed. */
    public record Status(String status, int percent, String message) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String tag;
        final Instant created = Instant.now();
        volatile Instant finished;
        volatile String status = "pending";
        volatile int percent;
        volatile String message = "Waiting to start";

        /** digest -> {total, completed}; touched only by the job's own thread. */
        final Map<String, long[]> layers = new LinkedHashMap<>();

        Job(String tag) {
            this.tag = tag;
        }

        boolean active() {
            return "pending".equals(status) || "running".equals(status);
        }

        Status snapshot() {
            return new Status(status, percent, message);
        }
    }

    private final LocalModelOllamaGateway ollama;
    private final ExecutorService executor =
            TenantAwareExecutors.newFixedThreadPool(
                    MAX_ACTIVE, TenantAwareExecutors.threadFactory("local-model-pull", true));
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public LocalModelPullService(LocalModelOllamaGateway ollama) {
        this.ollama = ollama;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Starts (or joins) the pull of {@code tag}. The caller has already validated the tag.
     *
     * @return the job id
     * @throws TooManyPullsException when {@link #MAX_ACTIVE} jobs are already in flight
     */
    public String start(String tag) {
        Job job;
        synchronized (jobs) {
            prune();
            for (Job existing : jobs.values()) {
                if (existing.active() && existing.tag.equalsIgnoreCase(tag)) {
                    return existing.id;
                }
            }
            if (jobs.values().stream().filter(Job::active).count() >= MAX_ACTIVE) {
                throw new TooManyPullsException();
            }
            job = new Job(tag);
            jobs.put(job.id, job);
        }
        try {
            executor.execute(TenantAwareExecutors.platform(() -> run(job)));
        } catch (RejectedExecutionException e) {
            fail(job, "Downloads are shutting down.");
        }
        return job.id;
    }

    public Optional<Status> status(String jobId) {
        synchronized (jobs) {
            Job job = jobs.get(jobId);
            return job == null ? Optional.empty() : Optional.of(job.snapshot());
        }
    }

    private void run(Job job) {
        job.status = "running";
        job.message = "Starting";
        try {
            ollama.pull(job.tag, event -> progress(job, event));
            job.percent = 100;
            job.message = "Installed";
            job.status = "done";
            log.info("Local model '{}' installed.", job.tag);
            // Load it now, while the user is still on the setup screen, so the first chat does not
            // also pay the cold start. Already on the pull thread; the status above is final.
            ollama.warm(job.tag);
        } catch (LocalModelOllamaGateway.PullException e) {
            fail(job, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Local model pull '{}' failed unexpectedly.", job.tag, e);
            fail(job, "The download failed.");
        } finally {
            job.finished = Instant.now();
        }
    }

    private void fail(Job job, String message) {
        log.warn("Local model pull '{}' failed: {}", job.tag, message);
        job.message = message;
        job.status = "failed";
        job.finished = Instant.now();
    }

    private static void progress(Job job, JsonNode event) {
        String text = event.path("status").asText("");
        if (!text.isBlank()) {
            job.message = text.length() > 120 ? text.substring(0, 120) : text;
        }
        String digest = event.path("digest").asText("");
        long total = event.path("total").asLong(0);
        if (!digest.isEmpty() && total > 0) {
            job.layers.put(
                    digest, new long[] {total, Math.min(total, event.path("completed").asLong(0))});
        }
        long sumTotal = 0;
        long sumDone = 0;
        for (long[] layer : job.layers.values()) {
            sumTotal += layer[0];
            sumDone += layer[1];
        }
        if (sumTotal > 0) {
            job.percent = (int) Math.min(99, sumDone * 100 / sumTotal);
        }
    }

    /** Drops finished jobs past their retention and the oldest finished ones past the cap. */
    private void prune() {
        Instant cutoff = Instant.now().minus(RETAIN_FINISHED);
        jobs.values()
                .removeIf(j -> !j.active() && j.finished != null && j.finished.isBefore(cutoff));
        if (jobs.size() > MAX_RETAINED) {
            List<Job> finished =
                    new ArrayList<>(jobs.values().stream().filter(j -> !j.active()).toList());
            finished.sort(Comparator.comparing(j -> j.created));
            for (Job j : finished) {
                if (jobs.size() <= MAX_RETAINED) {
                    break;
                }
                jobs.remove(j.id);
            }
        }
    }
}
