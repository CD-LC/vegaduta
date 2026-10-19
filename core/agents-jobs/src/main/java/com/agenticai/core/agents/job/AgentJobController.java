package com.agenticai.core.agents.job;

import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for agent jobs.
 *
 * <p>Thin by design - every access check, every clamp and every state decision
 * lives in {@link AgentJobService}, so the scheduler and event-trigger PRs get
 * identical enforcement without going through HTTP. The one thing this class
 * does own is the SHAPE of the contract: {@code 202 Accepted} on submit, because
 * the work has been durably accepted and has emphatically not been done yet.
 *
 * <p>The tenant-wide operator list lives here too rather than in the
 * observability PR's controller. Two controllers both serving "list jobs" would
 * inevitably drift in their filtering and their access rules; there is one.
 */
@RestController
public class AgentJobController {

    private final AgentJobService jobService;

    public AgentJobController(AgentJobService jobService) {
        this.jobService = jobService;
    }

    /**
     * 202, never 200: the response body describes a QUEUED job, and returning
     * 200 would invite clients to treat {@code resultText} as populated.
     */
    @PostMapping("/api/agents/{agentId}/jobs")
    public ResponseEntity<AgentJobResponse> submit(
        @PathVariable UUID agentId,
        @Valid @RequestBody AgentJobStartRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobService.submit(agentId, request));
    }

    /**
     * @param status repeatable ({@code ?status=RUNNING&status=QUEUED}) and
     *     absent means "all". The literal {@code live} is a sentinel expanding
     *     to {@link AgentJobStatus#live()} - the three non-terminal states -
     *     so a console asking "what is still going?" cannot drift from the
     *     server's own definition of live, which is also what the schedule
     *     overlap guard and V208's unique index are built on.
     */
    @GetMapping("/api/agents/{agentId}/jobs")
    public ResponseEntity<List<AgentJobSummaryResponse>> listForAgent(
        @PathVariable UUID agentId,
        @RequestParam(required = false) List<String> status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        Set<AgentJobStatus> statuses = parseStatuses(status);
        List<AgentJobSummaryResponse> rows = jobService.listForAgent(agentId, statuses, limit, offset);
        long total = jobService.countForTenant(statuses, agentId);
        long start = Math.max(offset, 0);
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .header("X-Has-More", String.valueOf(total > start + Math.min(Math.max(limit, 1), 200)))
            .body(rows);
    }

    @GetMapping("/api/agents/{agentId}/jobs/{jobId}")
    public AgentJobResponse get(@PathVariable UUID agentId, @PathVariable UUID jobId) {
        return jobService.get(agentId, jobId);
    }

    /**
     * Idempotent: a job that has already finished returns its current row with
     * 200, not a 409. See {@link AgentJobService#cancel}.
     */
    @PostMapping("/api/agents/{agentId}/jobs/{jobId}/cancel")
    public AgentJobResponse cancel(@PathVariable UUID agentId, @PathVariable UUID jobId) {
        return jobService.cancel(agentId, jobId);
    }

    /**
     * Tenant-wide operator view. Rows the caller may not VIEW are filtered out
     * per row rather than the whole request being refused - an operator with
     * partial visibility should see their own jobs, not a 403.
     *
     * <p><b>That per-row filtering happens AFTER paging</b>, so a full page can
     * legitimately come back with fewer rows than {@code limit} - or empty -
     * without meaning there is no more history. A client must page on the
     * request's offset, never on "did I get a full page?". The alternative
     * (pre-computing the visible agent set as a query predicate) would mean a
     * second implementation of {@code AgentService#requireAccess}, and two
     * implementations of "may this caller see this agent" is exactly how the two
     * drift apart.
     *
     * @param status same repeatable parameter and same {@code live} sentinel as
     *     {@link #listForAgent}.
     */
    @GetMapping("/api/agent-jobs")
    public ResponseEntity<List<AgentJobSummaryResponse>> listForTenant(
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) UUID agentId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        Set<AgentJobStatus> statuses = parseStatuses(status);
        List<AgentJobSummaryResponse> rows = jobService.listForTenant(statuses, agentId, limit, offset);
        // X-Total-Count counts the rows matching the filters before the per-row visibility
        // filter (see above), and X-Has-More is computed from the raw page window, so it stays
        // truthful when a full page comes back short because rows were filtered out.
        long total = jobService.countForTenant(statuses, agentId);
        int size = Math.min(Math.max(limit, 1), 200);
        long start = Math.max(offset, 0);
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(total))
            .header("X-Has-More", String.valueOf(total > start + size))
            .body(rows);
    }

    /**
     * Parses the repeatable {@code status} parameter, expanding the {@code live}
     * sentinel.
     *
     * <p>Taken as {@code List<String>} rather than {@code List<AgentJobStatus>}
     * precisely so the sentinel can exist: Spring's own enum conversion would
     * reject {@code live} with an opaque 400 before this method ever ran.
     * Unknown values still produce a 400 - through {@code
     * IllegalArgumentException}, which {@code GlobalExceptionHandler} already
     * maps - but with a message naming the value and listing what is valid.
     *
     * @return an empty set for "no filter". Never {@code null}.
     */
    private static Set<AgentJobStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<AgentJobStatus> statuses = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String token = value.trim();
            if (LIVE_SENTINEL.equalsIgnoreCase(token)) {
                statuses.addAll(AgentJobStatus.live());
                continue;
            }
            try {
                statuses.add(AgentJobStatus.valueOf(token.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                    "Unknown job status '" + token + "'. Valid values are "
                        + java.util.Arrays.toString(AgentJobStatus.values()) + " or '" + LIVE_SENTINEL + "'.");
            }
        }
        return statuses;
    }

    private static final String LIVE_SENTINEL = "live";
}
