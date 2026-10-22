package com.agenticai.core.chat.langfuse;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin, hand-rolled client for Langfuse's public Scores API ({@code POST
 * {langfuseBaseUrl}/api/public/scores}) - Langfuse has no official Java SDK (confirmed via
 * Langfuse's own docs, July 2026: the OpenTelemetry endpoint is the documented ingestion path for
 * traces/ observations, and {@code /api/public/scores} - not the older, now- deprecated {@code
 * /api/public/ingestion} batch endpoint - is the current documented way to attach a score to a
 * trace or session by id; see
 * https://langfuse.com/docs/evaluation/evaluation-methods/scores-via-sdk and
 * https://langfuse.com/docs/api-and-data-platform/features/public-api).
 *
 * <p>Used to push the two verdict/reward signals this codebase computes but never previously
 * reported anywhere Langfuse-visible: {@code LlmQualityJudgeService}'s (core/agents) sampled
 * LLM-as-judge verdict, and {@code RewardService}'s (core/rl) composite RL reward per episode. Both
 * call sites already have either the {@code traceId} {@link
 * com.agenticai.core.chat.LlmMetricsAdvisor} captured from the current OTel span (see that class)
 * or a {@code sessionId} - Langfuse's scores endpoint accepts either (a session-level score when no
 * single trace applies, e.g. an RL episode that spanned several LLM calls/traces).
 *
 * <p><b>Config reuse, not invention</b> - deliberately does NOT introduce any new property. It
 * derives the Langfuse base URL from the exact same {@code management.otlp.tracing.endpoint}
 * property the OTLP trace exporter already uses (stripping the well-known {@code
 * /api/public/otel/v1/traces} suffix Langfuse's own OTLP ingestion path documents), and reuses the
 * exact same {@code LANGFUSE_OTEL_AUTH_HEADER} pre-built "Basic base64(publicKey: secretKey)" value
 * the OTLP exporter's {@code management.otlp.tracing. headers.Authorization} already sends -
 * Langfuse's public API accepts this identical Basic-Auth pair on every one of its endpoints, so no
 * separate credential wiring is needed.
 *
 * <p><b>Trace-id caveat (assumption, not confirmed against a live Langfuse instance - see this
 * build's final report)</b>: this class assumes the 32-lowercase-hex-char OTel trace id captured by
 * {@code LlmMetricsAdvisor} is directly usable, unmodified, as Langfuse's own {@code traceId} for a
 * trace that arrived via Langfuse's native OTLP ingestion path. This is inferred, not verified
 * against a live Langfuse instance: Langfuse's docs confirm its trace ids are "32 hexchar"
 * (matching OTel's own W3C trace-id format exactly), and a public Langfuse GitHub discussion
 * (orgs/langfuse discussion #14652, "Map langfuse.score.* span attributes to scores on the OTLP
 * ingestion path") explicitly describes OTel-only pipelines needing to call the scores
 * API/ingestion path separately from tracing - consistent with "the OTel trace id IS the Langfuse
 * trace id for OTLP-ingested traces" - but no Langfuse doc page states this equivalence in so many
 * words. Verify against a real self-hosted Langfuse instance (push one score, check it lands on the
 * expected trace in the UI) before relying on this in production.
 *
 * <p>Fails open, always - a scoring call is a nice-to-have observability signal, never something
 * that should affect (or even slow down) the real judge/RL code path calling it. Every failure -
 * Langfuse not configured, unreachable, a 4xx/5xx response - is caught and logged at WARN, never
 * thrown or propagated.
 */
@Component
public class LangfuseScoreClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseScoreClient.class);

    /**
     * Langfuse's documented native OTLP ingestion path - see application.yml's
     * management.otlp.tracing.endpoint.
     */
    private static final String OTLP_TRACES_SUFFIX = "/api/public/otel/v1/traces";

    private static final String SCORES_PATH = "/api/public/scores";

    /** Langfuse's four supported score value types (scores-via-sdk docs, July 2026). */
    public enum ScoreDataType {
        NUMERIC,
        CATEGORICAL,
        BOOLEAN,
        TEXT
    }

    private final String authHeader;
    private final String scoresUrl;
    private final RestClient restClient;

    /**
     * @param restClientBuilder the AUTO-CONFIGURED builder, injected rather than a bare {@code
     *     RestClient.builder()} (2026-07-29 security audit). A hand-built one bypasses Spring
     *     Boot's HTTP client auto-configuration entirely, which meant this client had no connect
     *     and no read timeout: a Langfuse instance that hung would have parked the judge/RL thread
     *     that called it indefinitely, exactly contrary to this class's own "never even slow down
     *     the caller" contract. Taking the shared builder picks up {@code spring.http.client.*} -
     *     and the GLOBAL values are the right ones here, not core/chat's much longer LLM ones: this
     *     is a small observability POST, not a completion.
     */
    public LangfuseScoreClient(
            RestClient.Builder restClientBuilder,
            @Value(
                            "${management.otlp.tracing.endpoint:http://langfuse-web:3000/api/public/otel/v1/traces}")
                    String otlpTracesEndpoint,
            @Value("${LANGFUSE_OTEL_AUTH_HEADER:}") String authHeader) {
        this.authHeader = authHeader;
        this.scoresUrl = deriveScoresUrl(otlpTracesEndpoint);
        this.restClient = restClientBuilder.build();
    }

    private static String deriveScoresUrl(String otlpTracesEndpoint) {
        if (otlpTracesEndpoint == null || otlpTracesEndpoint.isBlank()) {
            return SCORES_PATH;
        }
        String base =
                otlpTracesEndpoint.endsWith(OTLP_TRACES_SUFFIX)
                        ? otlpTracesEndpoint.substring(
                                0, otlpTracesEndpoint.length() - OTLP_TRACES_SUFFIX.length())
                        : otlpTracesEndpoint;
        return base + SCORES_PATH;
    }

    /**
     * Pushes one score. {@code traceId}/{@code sessionId} may not both be null (Langfuse needs
     * something to attach the score to) - both are accepted together when known, so a
     * session-scoped view of the score still lines up with the specific trace when Langfuse can
     * resolve both.
     *
     * @param id stable idempotency key (Langfuse upserts on a repeat {@code id}, per its ingestion
     *     docs) - pass a deterministic value (e.g. {@code "rl-reward-" + episodeId}) for a score
     *     that may be recomputed/resubmitted, or {@code null} to let Langfuse assign one for a
     *     genuinely new, one-off event (e.g. each sampled judge verdict).
     * @param traceId 32-hex-char OTel/Langfuse trace id, or {@code null}.
     * @param sessionId Langfuse session id (this platform's chat/task session id), or {@code null}.
     * @param name score name/key, e.g. {@code "llm_quality_ok"} or {@code "rl_reward"}.
     * @param dataType the score's value type - must match {@code value}'s shape.
     * @param value a {@code Double} for NUMERIC, a {@code String} for CATEGORICAL/TEXT, or a {@code
     *     Boolean}/{@code 0|1} for BOOLEAN.
     * @param comment optional free-text context (e.g. the judge's flagged reason), or {@code null}.
     */
    public void pushScore(
            String id,
            String traceId,
            String sessionId,
            String name,
            ScoreDataType dataType,
            Object value,
            String comment) {
        if (authHeader == null || authHeader.isBlank()) {
            // Same "off is a real no-op" posture as management.tracing.sampling.probability=0.0 -
            // Langfuse isn't configured in this deployment, so there is nothing to push to.
            return;
        }
        if ((traceId == null || traceId.isBlank()) && (sessionId == null || sessionId.isBlank())) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (id != null && !id.isBlank()) {
                body.put("id", id);
            }
            if (traceId != null && !traceId.isBlank()) {
                body.put("traceId", traceId);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                body.put("sessionId", sessionId);
            }
            body.put("name", name);
            body.put("value", value);
            body.put("dataType", dataType.name());
            if (comment != null && !comment.isBlank()) {
                body.put("comment", comment);
            }

            restClient
                    .post()
                    .uri(scoresUrl)
                    .header("Authorization", authHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Never let a scoring call break or slow down the real judge/RL path it was called
            // from.
            log.warn(
                    "Failed to push Langfuse score '{}' (traceId={}, sessionId={}) to {} - {}",
                    name,
                    traceId,
                    sessionId,
                    scoresUrl,
                    e.getMessage());
        }
    }
}
