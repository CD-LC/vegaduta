package com.agenticai.core.knowledge;

import static com.agenticai.core.knowledge.SidecarHttpSupport.describe;
import static com.agenticai.core.knowledge.SidecarHttpSupport.describeClientError;
import static com.agenticai.core.knowledge.SidecarHttpSupport.withConnectRetry;

import com.agenticai.core.knowledge.dto.SidecarCaptionsResponse;
import com.agenticai.core.knowledge.dto.SidecarRenderJobResponse;
import com.agenticai.core.knowledge.dto.SidecarTtsResponse;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The sidecar video-pipeline media endpoints ({@code /tts}, {@code /captions}, {@code /render}).
 * One client per endpoint family behind the {@link SidecarClient} facade; extracted unchanged.
 */
final class SidecarMediaClient {

    private final RestClient mediaRestClient;
    private final RestClient queryRestClient;
    private final Consumer<HttpHeaders> applyInternalToken;

    SidecarMediaClient(
            RestClient mediaRestClient,
            RestClient queryRestClient,
            Consumer<HttpHeaders> applyInternalToken) {
        this.mediaRestClient = mediaRestClient;
        this.queryRestClient = queryRestClient;
        this.applyInternalToken = applyInternalToken;
    }

    // --- Video pipeline media endpoints (Stage 2/3) --------------------------
    //
    // These four exist because a WORKFLOW cannot reach the sidecar directly and
    // should not be able to. The workflow `http` node delegates to
    // WebPageFetcher#validatePublicHttpUri, which requires every resolved
    // address for the host to be public - loopback, RFC-1918 and link-local all
    // refuse - because the URL in an http node comes straight out of
    // tenant-authored config. The sidecar is internal-only by design. So the
    // only correct way for a workflow to use /tts, /captions or /render is for
    // CORE to make the call in Java, where sidecar access is normal and already
    // authenticated, and for the tenant to supply no URL at all. That is what
    // WorkflowNodeExecutor's MEDIA node does, through these methods.
    //
    // Requests are Map<String, Object> rather than typed records, deliberately
    // and unlike every method above. A MEDIA node's payload is assembled from a
    // tenant's own workflow JSON and is validated by the sidecar's own Pydantic
    // models, which return a 422 naming the exact offending field - a strictly
    // better error than anything a Java DTO would produce, and one place to
    // change when the render request grows a field instead of two. Responses ARE
    // typed, because core reads them.

    /**
     * Sarvam narration, one segment per episode beat (sidecar {@code POST /tts}).
     *
     * <p>The returned per-segment {@code durationSec} values are MEASURED from the synthesized
     * audio, not estimated, and the render depends on that: a B-roll clip is looped or trimmed to
     * its beat's measured narration length, so an estimate here would put A/V drift into every
     * episode.
     */
    public SidecarTtsResponse tts(Map<String, Object> request) {
        try {
            SidecarTtsResponse response =
                    withConnectRetry(
                            "/tts",
                            () ->
                                    mediaRestClient
                                            .post()
                                            .uri("/tts")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(request)
                                            .retrieve()
                                            .body(SidecarTtsResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /tts returned an empty response", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            // 4xx here is the caller's problem and the sidecar says why - a
            // missing SARVAM_API_KEY, or Sarvam's own "Text must contain at
            // least one character from the allowed languages". Both name the
            // real fault, so the message is passed through rather than replaced.
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/tts", e), e);
        }
    }

    /**
     * Word-level captions as SRT from already-synthesized audio (sidecar {@code POST /captions}).
     */
    public SidecarCaptionsResponse captions(Map<String, Object> request) {
        try {
            SidecarCaptionsResponse response =
                    withConnectRetry(
                            "/captions",
                            () ->
                                    mediaRestClient
                                            .post()
                                            .uri("/captions")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(request)
                                            .retrieve()
                                            .body(SidecarCaptionsResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /captions returned an empty response", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/captions", e), e);
        }
    }

    /**
     * Queue an ffmpeg render (sidecar {@code POST /render}, answers 202).
     *
     * <p>Returns as soon as the job is queued. The render itself is minutes of single-core x264,
     * which outlives this client's read timeout, the workflow engine's per-node step timeout and -
     * on a real deployment - Cloudflare's request ceiling. Poll {@link #renderStatus(String)}.
     */
    public SidecarRenderJobResponse renderSubmit(Map<String, Object> request) {
        try {
            SidecarRenderJobResponse response =
                    withConnectRetry(
                            "/render",
                            () ->
                                    queryRestClient
                                            .post()
                                            .uri("/render")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .headers(applyInternalToken)
                                            .body(request)
                                            .retrieve()
                                            .body(SidecarRenderJobResponse.class));
            if (response == null || response.jobId() == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /render accepted the request but returned no job id", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/render", e), e);
        }
    }

    /** Read a render job's state (sidecar {@code GET /render/{jobId}}). */
    public SidecarRenderJobResponse renderStatus(String jobId) {
        try {
            SidecarRenderJobResponse response =
                    withConnectRetry(
                            "/render/{jobId}",
                            () ->
                                    queryRestClient
                                            .get()
                                            .uri("/render/{jobId}", jobId)
                                            .headers(applyInternalToken)
                                            .retrieve()
                                            .body(SidecarRenderJobResponse.class));
            if (response == null) {
                throw new SidecarCommunicationException(
                        "Sidecar /render/" + jobId + " returned an empty response", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            // A 404 here means the job id is unknown OR was evicted - the
            // sidecar retains only the last RENDER_RETAIN_JOBS finished jobs,
            // so "completed" does not imply "still readable" forever.
            throw new InvalidKnowledgeUploadException(describeClientError(e));
        } catch (RestClientException e) {
            throw new SidecarCommunicationException(describe("/render/" + jobId, e), e);
        }
    }
}
