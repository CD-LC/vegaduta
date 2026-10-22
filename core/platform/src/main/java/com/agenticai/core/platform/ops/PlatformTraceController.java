package com.agenticai.core.platform.ops;

import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceDetailResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceListResponse;
import com.agenticai.core.platform.ops.PlatformTraceDtos.TraceMetricsResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM traces for the platform console - a read-only proxy over Langfuse
 * ({@link PlatformTraceService}). Gated by the blanket {@code SecurityConfig}
 * rule {@code /api/platform/** -> ROLE_platform-admin}. Langfuse being absent
 * or down answers 200 with {@code available=false} and a {@code reason}.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/traces")
public class PlatformTraceController {

    private final PlatformTraceService traces;

    public PlatformTraceController(PlatformTraceService traces) {
        this.traces = traces;
    }

    @GetMapping
    public TraceListResponse list(@RequestParam(name = "from", required = false) String from,
                                  @RequestParam(name = "to", required = false) String to,
                                  @RequestParam(name = "tenantId", required = false) String tenantId,
                                  @RequestParam(name = "userId", required = false) String userId,
                                  @RequestParam(name = "name", required = false) String name,
                                  @RequestParam(name = "page", required = false) Integer page,
                                  @RequestParam(name = "limit", required = false) Integer limit) {
        return traces.list(from, to, tenantId, userId, name, page, limit);
    }

    @GetMapping("/metrics")
    public TraceMetricsResponse metrics(@RequestParam(name = "from", required = false) String from,
                                        @RequestParam(name = "to", required = false) String to) {
        return traces.metrics(from, to);
    }

    @GetMapping("/{id}")
    public TraceDetailResponse detail(@PathVariable("id") String id) {
        return traces.detail(id);
    }
}
