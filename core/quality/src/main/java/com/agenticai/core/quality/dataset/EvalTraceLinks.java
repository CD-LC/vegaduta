package com.agenticai.core.quality.dataset;

import com.agenticai.core.chat.LlmCallLogService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Langfuse trace links on eval run results (2026-09): the trace id of a replayed case (read from core/chat's
 * durable per-call log) and the browser-facing Langfuse URL for it. Split out of {@link EvalDatasetService},
 * which builds it from its constructor settings and exposes both reads unchanged.
 */
final class EvalTraceLinks {

    private static final Logger log = LoggerFactory.getLogger(EvalTraceLinks.class);

    /**
     * Same OTLP path suffix {@code LangfuseScoreClient} (core/chat) and {@code LangfuseEvalClient}
     * (this module's {@code eval} package) strip to reach the Langfuse API base - the one derivation every Langfuse-touching
     * class in this codebase shares, so they can never disagree about where Langfuse is.
     */
    static final String OTLP_TRACES_SUFFIX = "/api/public/otel/v1/traces";

    /**
     * core/chat's durable per-call log - the read-side source of the replayed turn's trace id, via
     * {@code LlmCallLogService#mostRecentTraceIdForSession}. {@link ObjectProvider} so a module-scoped
     * test slice without core/chat's beans still works.
     */
    private final ObjectProvider<LlmCallLogService> llmCallLogService;

    /**
     * Browser-facing Langfuse origin, or null when Langfuse is not configured - see {@link
     * #resolveLangfuseBrowserBase}.
     */
    private final String langfuseBrowserBase;

    private final String langfuseProjectId;

    /**
     * @param langfuseHost the browser-facing Langfuse origin ({@code LANGFUSE_HOST}, the same
     *     variable deploy's Grafana trace-link provisioning reads) - the in-cluster OTLP endpoint
     *     ({@code langfuse-web:3000}) is not something a human's browser can open, so it is only
     *     the fallback.
     * @param otlpTracesEndpoint the exact property the OTLP exporter and {@code
     *     LangfuseScoreClient} use; stripped of the OTLP path to get the fallback base.
     * @param langfuseAuthHeader presence = "Langfuse is configured here"; blank = no trace links
     *     are ever rendered, since a trace id logged with tracing on but no Langfuse deployed
     *     points at nothing.
     * @param langfuseProjectId {@code LANGFUSE_INIT_PROJECT_ID}, the value Langfuse's bootstrap
     *     uses as the project's real id (deploy's .env.example explains why there is no separate
     *     PROJECT_ID var). When set, links use Langfuse's documented project-scoped trace URL; when
     *     blank, the project-agnostic {@code /trace/{id}} redirect route.
     */
    EvalTraceLinks(
            ObjectProvider<LlmCallLogService> llmCallLogService,
            String langfuseHost,
            String otlpTracesEndpoint,
            String langfuseAuthHeader,
            String langfuseProjectId) {
        this.llmCallLogService = llmCallLogService;
        this.langfuseBrowserBase =
                resolveLangfuseBrowserBase(langfuseHost, otlpTracesEndpoint, langfuseAuthHeader);
        this.langfuseProjectId =
                langfuseProjectId == null || langfuseProjectId.isBlank()
                        ? null
                        : langfuseProjectId.trim();
    }

    /**
     * The trace id of the replayed turn for the {@code index}-th case of a run, or null. {@code
     * EvalRunService#executeCase} replays each case under the session id {@code "eval:<runId>:<n>"}
     * with {@code n} the 0-based loop counter over cases ordered by (position, createdAt) - the
     * same order {@code listCaseResults} returns results in, so the index of a result in that list
     * IS its {@code n}. {@code LlmMetricsAdvisor} (core/chat) durably logs every call's trace id
     * against that session id, and {@code LlmCallLogService#mostRecentTraceIdForSession} is the
     * existing lookup {@code LlmQualityJudgeService} already uses for the same purpose on live
     * turns. Read-time lookup, no new column: a result row is append-only provenance and the log
     * row already exists.
     *
     * <p>Null (never an exception) when the log service isn't wired, the replay never reached the
     * model (an ERROR case has no call row), or tracing was off - honest absence on a read path
     * that must not 500 over a link.
     */
    String traceIdForRunCase(UUID runId, int index) {
        LlmCallLogService service = llmCallLogService.getIfAvailable();
        if (service == null || runId == null || index < 0) {
            return null;
        }
        try {
            return EvalCaseInputs.blankToNull(service.mostRecentTraceIdForSession("eval:" + runId + ":" + index));
        } catch (Exception e) {
            log.debug(
                    "Trace-id lookup failed for eval run {} case #{} - omitting link ({})",
                    runId,
                    index,
                    e.toString());
            return null;
        }
    }

    /**
     * Browser-facing Langfuse deep link for a trace id, or null when Langfuse is not configured
     * (see the constructor) or the id is blank. Project-scoped ({@code
     * /project/{id}/traces/{traceId}}, the shape Langfuse documents and deploy's Grafana
     * derived-field uses) when the project id is known; otherwise Langfuse's project-agnostic
     * {@code /trace/{traceId}} route, which resolves the project from the signed-in user's access
     * and redirects.
     */
    String langfuseTraceUrl(String traceId) {
        return langfuseTraceUrl(langfuseBrowserBase, langfuseProjectId, traceId);
    }

    static String langfuseTraceUrl(String browserBase, String projectId, String traceId) {
        if (browserBase == null || traceId == null || traceId.isBlank()) {
            return null;
        }
        String id = traceId.trim();
        return projectId != null
                ? browserBase + "/project/" + projectId + "/traces/" + id
                : browserBase + "/trace/" + id;
    }

    /**
     * Null when {@code authHeader} is blank (Langfuse not configured - no link is better than a
     * dead one); else {@code LANGFUSE_HOST} when set, else the OTLP endpoint minus its documented
     * path - the exact fallback {@code LangfuseScoreClient#deriveScoresUrl} performs. Trailing
     * slashes trimmed so concatenation never yields {@code //}.
     */
    static String resolveLangfuseBrowserBase(
            String langfuseHost, String otlpTracesEndpoint, String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String base;
        if (langfuseHost != null && !langfuseHost.isBlank()) {
            base = langfuseHost.trim();
        } else if (otlpTracesEndpoint != null && !otlpTracesEndpoint.isBlank()) {
            String endpoint = otlpTracesEndpoint.trim();
            base =
                    endpoint.endsWith(OTLP_TRACES_SUFFIX)
                            ? endpoint.substring(0, endpoint.length() - OTLP_TRACES_SUFFIX.length())
                            : endpoint;
        } else {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isEmpty() ? null : base;
    }
}
