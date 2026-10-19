package com.agenticai.core.agents.research;

import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import com.agenticai.core.agents.research.dto.ResearchRunStartRequest;
import com.agenticai.core.agents.research.dto.ResearchStatusResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for research runs. Thin: availability, validation, the daily cap and access all live in
 * {@link ResearchRunService}. Authenticated like every other {@code /api/**} route (no SecurityConfig
 * entry); identity comes from the tenant context, never from the request.
 */
@RestController
@RequestMapping("/api/research")
public class ResearchRunController {

    private final ResearchRunService researchRunService;

    public ResearchRunController(ResearchRunService researchRunService) {
        this.researchRunService = researchRunService;
    }

    /** Always 200 for a signed-in caller, including when the feature is off - that is what it reports. */
    @GetMapping("/status")
    public ResearchStatusResponse status() {
        return researchRunService.status();
    }

    /** 202: the run is durably queued, not done. The body is the job row to poll. */
    @PostMapping("/runs")
    public ResponseEntity<AgentJobResponse> start(@RequestBody ResearchRunStartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(researchRunService.start(request));
    }

    /**
     * The body stays a bare array (the web client reads it as one); whether more rows follow the page is
     * in the {@code X-Has-More} response header ({@code true}/{@code false}).
     */
    @GetMapping("/runs")
    public ResponseEntity<List<AgentJobSummaryResponse>> list(
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        AgentJobService.SummarySlice slice = researchRunService.listPage(limit, offset);
        return ResponseEntity.ok()
            .header(HAS_MORE_HEADER, String.valueOf(slice.hasMore()))
            .body(slice.items());
    }

    static final String HAS_MORE_HEADER = "X-Has-More";

    @GetMapping("/runs/{jobId}")
    public AgentJobResponse get(@PathVariable UUID jobId) {
        return researchRunService.get(jobId);
    }

    /** Idempotent, like the job cancel it delegates to: a finished run returns its row with 200. */
    @PostMapping("/runs/{jobId}/cancel")
    public AgentJobResponse cancel(@PathVariable UUID jobId) {
        return researchRunService.cancel(jobId);
    }
}
