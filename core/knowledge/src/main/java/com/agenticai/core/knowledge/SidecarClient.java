package com.agenticai.core.knowledge;

import com.agenticai.core.knowledge.dto.SidecarCaptionsResponse;
import com.agenticai.core.knowledge.dto.SidecarChunk;
import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.dto.SidecarRenderJobResponse;
import com.agenticai.core.knowledge.dto.SidecarRerankRequest;
import com.agenticai.core.knowledge.dto.SidecarRerankResponse;
import com.agenticai.core.knowledge.dto.SidecarTtsResponse;
import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import com.agenticai.core.knowledge.ocr.ActiveOcrConfig;
import com.agenticai.core.resilience.CircuitBreakerRegistry;
import com.agenticai.core.resilience.ResilienceGuard;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the Python sidecar's /embed endpoint. Stage 5 only - gated behind the "knowledge"
 * profile so Stages 1-4 are unaffected for anyone who hasn't stood up a pgvector-enabled Postgres
 * yet (see VectorSchemaInitializer and the README's Stage 5 section).
 *
 * <p><b>Timeouts (RAG audit fix, 2026-07-29).</b> This client previously ran on {@code
 * SimpleClientHttpRequestFactory}'s defaults - i.e. wait indefinitely - justified by {@link
 * #transcribeAudio}'s javadoc arguing the async job path can afford to block. That argument was
 * only ever true for transcription: {@code /embed} and {@code /extract} are ALSO called from live
 * HTTP request threads ({@code KnowledgeController.upload} runs synchronously to completion within
 * one request; every search embeds its query on the caller's thread), so a hung sidecar used to
 * hang tenant uploads and searches forever. The fix is per-operation timeouts, not one global one,
 * because the legitimate durations genuinely differ by an order of magnitude per call class:
 *
 * <ul>
 *   <li>{@link #QUERY_READ_TIMEOUT} (30s) - {@link #embedQuery} and {@link #rerank}: single-chunk
 *       embeds and small-shortlist cross-encoder scoring on live SEARCH threads. Sub-second when
 *       healthy (except a one-time cold model load, which 30s still covers); anything longer means
 *       the sidecar is wedged and the search should fail (or, for rerank, fall back) rather than
 *       hold the request thread.
 *   <li>{@link #INGEST_READ_TIMEOUT} (5 min) - {@link #embed} and {@link #extractText}:
 *       whole-document chunk+embed batches and PDF/OCR extraction on live UPLOAD threads. A 5MB
 *       document embedded on CPU legitimately takes minutes, and the uploader is already waiting on
 *       a synchronous response - generous, but still bounded.
 *   <li>{@link #TRANSCRIBE_READ_TIMEOUT} (30 min) - {@link #transcribeAudio}: only ever called from
 *       the background job runner (see that method's javadoc); CPU Whisper over a 25MB video
 *       genuinely takes many minutes. 30 minutes preserves the "long-running is fine here" property
 *       the old unbounded default was defending, while still eventually releasing a poller thread
 *       wedged on a dead-but-connected sidecar.
 *   <li>{@link #LIVE_TRANSCRIBE_READ_TIMEOUT} (45s, added 2026-08-06) - {@link
 *       #transcribeAudioLive}: the same sidecar Whisper call as {@link #transcribeAudio}, but for
 *       the ONE caller that runs it on a live voice-call request thread instead of the background
 *       job runner ({@code VoiceCallController#turn}'s synchronous fallback). A separate
 *       timeout/client, not a shortening of {@link #TRANSCRIBE_READ_TIMEOUT} itself - the
 *       background job runner's 30-minute need for large batch audio is real and unrelated to this
 *       call site.
 * </ul>
 *
 * Connect timeout is a single short {@link #CONNECT_TIMEOUT} (3s) for all four - establishing a TCP
 * connection to a compose-internal service either happens immediately or not at all.
 *
 * <p><b>Transport: JDK {@link HttpClient}, not {@code HttpURLConnection} (virtual-thread pinning
 * fix, 2026-07-29).</b> The whole app runs on {@code spring.threads.virtual.enabled: true}, so
 * every knowledge upload, extract, embed and search reaches this class on a virtual thread. {@code
 * SimpleClientHttpRequestFactory} is {@code HttpURLConnection}, whose {@code getInputStream()} is a
 * {@code synchronized} method that performs the blocking socket read <em>inside</em> the monitor.
 * Under JDK 21 (which is what production runs - {@code eclipse-temurin:21-jre-jammy}, see {@code
 * core/Dockerfile}) blocking inside {@code synchronized} PINS the carrier thread, so this class -
 * the single busiest outbound client in the app - could hold a carrier for the full read timeout,
 * up to {@link #TRANSCRIBE_READ_TIMEOUT}. With only as many carriers as CPUs, that silently
 * degrades the scheduler to a tiny fixed pool. JEP 491 removed {@code synchronized} pinning in JDK
 * 24, so this is invisible on a JDK 24 dev box and only ever bites in production - do not
 * "simplify" it back. {@link JdkClientHttpRequestFactory} ({@code java.net.http.HttpClient}) parks
 * the virtual thread instead of pinning its carrier.
 *
 * <p><b>The {@code outputStreaming(false)} fix is preserved - and, it turns out, repaired.</b> See
 * {@link #buildClient}: the request body must go out with a real {@code Content-Length} rather than
 * chunked transfer-encoding, or the empty-body-against-uvicorn bug documented there comes back.
 * Measuring the old code against a local server while making this change turned up that it was
 * ALREADY sending chunked - {@code SimpleClientHttpRequestFactory .setOutputStreaming(boolean)} is
 * deprecated and an outright no-op in Spring 6.2.x (its compiled body is a bare {@code return}), so
 * the documented fix had been silently doing nothing since some Spring 6.1/6.2 upgrade.
 *
 * <p><b>Retry.</b> {@link #withConnectRetry} retries exactly once, only when the failure is a
 * {@link ConnectException} somewhere in the cause chain - i.e. the request provably never reached
 * the sidecar (refused/unreachable), so retrying is safe for every operation regardless of cost or
 * idempotency concerns, and cheap (the failure was immediate, not a timeout). Read timeouts and
 * HTTP error responses are deliberately NOT retried: the sidecar did receive the request, and
 * blindly re-running a multi-minute embed/extract/transcribe would double the caller's wait for an
 * operation that is expensive even though it happens to be side-effect-free.
 *
 * <p><b>Internal auth (2026-08-07).</b> The sidecar now requires {@code X-Internal-Token} on every
 * endpoint except {@code /health} (it was the last unauthenticated service in the stack - see
 * {@code sidecar/app/ main.py}'s require_internal_token). Every call below sends the header when
 * {@code SIDECAR_SHARED_SECRET} is configured; a blank secret sends no header at all, which only
 * works against a sidecar running with {@code SIDECAR_AUTH_REQUIRED=false} (the bare-local-dev
 * escape hatch). Resolved once at construction, same as {@code baseUrl} - the compose deployment
 * restarts both containers to rotate the shared value, so per-call re-resolution
 * (ToolSandboxClient's style, justified there by per-tenant context on the same header pass) would
 * buy nothing here.
 */
@Component
@Profile("knowledge")
public class SidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    // Sidecar's EmbedRequest.chunk_size accepts up to 200 (see main.py).
    // Used for single-text query embedding - see embedQuery()'s javadoc for
    // why that reuses /embed instead of calling /embed-query directly.
    private static final int QUERY_CHUNK_SIZE = 200;

    // Per-operation timeouts - see the class javadoc for the reasoning
    // behind each value and why one global timeout would be wrong here.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration QUERY_READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INGEST_READ_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration TRANSCRIBE_READ_TIMEOUT = Duration.ofMinutes(30);
    // Live-request-thread bound for VoiceCallController#turn's synchronous
    // sidecar-Whisper fallback (2026-08-06 follow-up to the RAG audit above)
    // - see transcribeAudioLive()'s javadoc for why this is a fifth, distinct
    // call class rather than a tuning of TRANSCRIBE_READ_TIMEOUT: that value
    // is sized for the background job runner, the only caller transcribeAudio()
    // contracts for, and a live voice turn borrowing its 30-minute budget
    // would hang the call, not release a poller thread. 45s mirrors this
    // class's existing "live thread" reasoning for QUERY_READ_TIMEOUT (30s)
    // with headroom for audio being genuinely slower to transcribe than a
    // single query embed.
    private static final Duration LIVE_TRANSCRIBE_READ_TIMEOUT = Duration.ofSeconds(45);
    // Video pipeline media endpoints. /tts serializes one Sarvam round trip per
    // narration beat (a 12-beat episode is 12 requests at ~2s each) and
    // /captions is a Whisper inference per beat, so both sit between the
    // ingest and transcribe profiles. /render is NOT covered by this: it
    // answers 202 immediately and is polled, precisely so no HTTP call has to
    // stay open for a multi-minute encode.
    private static final Duration MEDIA_READ_TIMEOUT = Duration.ofMinutes(10);

    /**
     * One shared JDK HTTP client behind all three per-operation {@link RestClient}s - they differ
     * only in read timeout, which lives on the {@link JdkClientHttpRequestFactory}, not on the
     * client, so sharing gives all three a single connection pool and a single selector thread
     * instead of three of each.
     *
     * <ul>
     *   <li>{@code connectTimeout} - the JDK client has no per-request connect timeout, so {@link
     *       #CONNECT_TIMEOUT} is configured here. It is the same 3s for all three profiles (see the
     *       class javadoc), which is exactly why sharing one client is possible.
     *   <li>{@code HTTP_1_1} - pinned deliberately. The JDK client defaults to {@code HTTP_2},
     *       which over cleartext http:// means an h2c {@code Upgrade} dance with the sidecar's
     *       uvicorn. This class already has a documented history of interop breakage with that
     *       exact server (see {@link #buildClient}); HTTP/1.1 is what {@code HttpURLConnection}
     *       spoke before this change, so pinning it keeps the wire behaviour identical rather than
     *       introducing a new variable alongside the transport swap.
     *   <li>{@code followRedirects(NORMAL)} - {@code HttpURLConnection} follows redirects by
     *       default and the JDK client does not ({@code Redirect.NEVER}). Starlette/FastAPI issues
     *       307s for trailing-slash mismatches, so restoring the old default here is behaviour
     *       preservation, not a new feature.
     *   <li>{@code executor} - without an explicit one, {@link JdkClientHttpRequestFactory} falls
     *       back to a {@code SimpleAsyncTaskExecutor} that starts a fresh <em>platform</em> thread
     *       per request body write. Handing the client a virtual-thread-per-task executor keeps the
     *       body writer on the same cheap threads as the rest of the app, which is the entire point
     *       of this change.
     * </ul>
     */
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .executor(
                            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("sidecar-client-"))
                    .build();

    /** Live search threads: embedQuery + rerank. Short read timeout. */
    private final RestClient queryRestClient;

    /** Live upload threads: embed (whole documents) + extractText. Minutes-scale read timeout. */
    private final RestClient ingestRestClient;

    /** Background job runner only: transcribeAudio. Very generous read timeout. */
    private final RestClient transcriptionRestClient;

    /** Live voice-call request threads only: transcribeAudioLive. Short read timeout. */
    private final RestClient liveTranscriptionRestClient;

    private final SidecarMediaClient media;

    /** Sent as X-Internal-Token when non-blank - see the class javadoc's internal-auth section. */
    private final String sharedSecret;

    /** /embed (circuit-broken) and the opt-in external {@link EmbeddingClient}. */
    private final SidecarEmbeddingClient embeddings;

    /** /extract and /transcribe. */
    private final SidecarConversionClient conversion;

    /** Spring constructor: picks up an {@link EmbeddingClient} bean only if one was opted in. */
    @Autowired
    public SidecarClient(
            @Value("${app.sidecar.base-url}")
                    String baseUrl, // Nested default keeps this out of application-knowledge.yml:
            // the
            // property namespace stays overridable per environment, while the
            // compose deployment only ever sets the SIDECAR_SHARED_SECRET env
            // var (the same value the sidecar and voice-agent read).
            @Value("${app.sidecar.shared-secret:${SIDECAR_SHARED_SECRET:}}") String sharedSecret,
            ObjectProvider<EmbeddingClient> embeddingClient) {
        this(baseUrl, sharedSecret, embeddingClient.getIfAvailable());
    }

    /** Sidecar-only embeddings (the default behaviour). */
    public SidecarClient(String baseUrl, String sharedSecret) {
        this(baseUrl, sharedSecret, (EmbeddingClient) null);
    }

    public SidecarClient(String baseUrl, String sharedSecret, EmbeddingClient embeddingOverride) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        this.embeddings = new SidecarEmbeddingClient(embeddingOverride, this::applyInternalToken);
        this.conversion = new SidecarConversionClient(this::applyInternalToken);
        this.queryRestClient = buildClient(baseUrl, QUERY_READ_TIMEOUT);
        this.ingestRestClient = buildClient(baseUrl, INGEST_READ_TIMEOUT);
        this.transcriptionRestClient = buildClient(baseUrl, TRANSCRIBE_READ_TIMEOUT);
        this.liveTranscriptionRestClient = buildClient(baseUrl, LIVE_TRANSCRIBE_READ_TIMEOUT);
        this.media =
                new SidecarMediaClient(
                        buildClient(baseUrl, MEDIA_READ_TIMEOUT),
                        queryRestClient,
                        this::applyInternalToken);
    }

    /** {@code sidecar} (default) or the opted-in {@link EmbeddingClient#provider()}. */
    public String embeddingProvider() {
        return embeddings.provider();
    }

    /**
     * Identity of the embedding source ({@code sidecar} or {@code provider:model:width}); recorded
     * per knowledge collection so a change is refused rather than silently mixing vectors.
     */
    public String embeddingFingerprint() {
        return embeddings.fingerprint();
    }

    /**
     * {@code app.sidecar.enabled} (default true). False = the deployment runs without the Python
     * sidecar (personal profile): sidecar-only capabilities answer with a clear {@link
     * FeatureUnavailableException} (or fail open, for rerank) instead of waiting on a connection
     * that can never succeed. Setter-injected so every constructor is unchanged.
     */
    private volatile boolean sidecarEnabled = true;

    @Autowired(required = false)
    void setSidecarEnabled(@Value("${app.sidecar.enabled:true}") boolean enabled) {
        this.sidecarEnabled = enabled;
        this.embeddings.setSidecarEnabled(enabled);
    }

    /**
     * {@code app.embeddings.provider=auto} with no Ollama in the environment: lets an Ollama
     * registered at run time take over from the sidecar. Setter-injected and optional, so every
     * constructor is unchanged.
     */
    @Autowired(required = false)
    void setRegisteredOllamaResolver(
            com.agenticai.core.knowledge.embedding.RegisteredOllamaEmbeddingResolver resolver) {
        this.embeddings.setDynamicEmbedder(resolver::resolve);
    }

    /** True unless {@code app.sidecar.enabled=false}. */
    public boolean isSidecarEnabled() {
        return sidecarEnabled;
    }

    private void requireSidecar(String capability) {
        if (!sidecarEnabled) {
            throw new FeatureUnavailableException(
                    capability
                            + " needs the Python sidecar, which is disabled (app.sidecar.enabled=false)."
                            + " Start the sidecar and set app.sidecar.enabled=true to use it.");
        }
    }

    /**
     * Adds {@code X-Internal-Token} when a secret is configured. A blank secret sends NO header
     * rather than an empty one - the sidecar's constant-time compare would reject an empty value
     * anyway, and an absent header keeps the unauthenticated-local-dev wire traffic byte-identical
     * to what it was before auth existed.
     */
    private void applyInternalToken(HttpHeaders headers) {
        if (!sharedSecret.isEmpty()) {
            headers.set("X-Internal-Token", sharedSecret);
        }
    }

    /**
     * <b>Read this before touching the request factory.</b> Two separate things are being preserved
     * here and they are easy to break independently.
     *
     * <p><b>1. No carrier-thread pinning.</b> The transport is the JDK's {@code
     * java.net.http.HttpClient} via {@link JdkClientHttpRequestFactory}, NOT {@code
     * SimpleClientHttpRequestFactory}/{@code HttpURLConnection} - see the class javadoc for why
     * that mattered enough to swap. Read timeout is per-profile and set on the factory ({@code
     * Duration}-typed here, unlike the old millis-int setter); connect timeout is on the shared
     * {@link #HTTP_CLIENT} because the JDK client has no per-request one.
     *
     * <p><b>2. A real {@code Content-Length}, never chunked transfer-encoding.</b> This is what the
     * old {@code requestFactory.setOutputStreaming(false)} line was meant to buy, and it is
     * load-bearing: it root-caused a bug where the first request to the sidecar succeeded but every
     * request after it on the same keep-alive connection arrived at FastAPI/uvicorn with an EMPTY
     * body. Buffering the body and sending a real {@code Content-Length} is the standard fix for
     * that class of interop issue between Java HTTP clients and lightweight ASGI servers.
     *
     * <p><b>The old line had stopped working.</b> Before changing anything, the previous code was
     * pointed at a local HTTP server to record what it actually put on the wire: {@code
     * Transfer-Encoding: chunked}, no {@code Content-Length}, on every request. The reason is that
     * {@code SimpleClientHttpRequestFactory.setOutputStreaming(boolean)} is deprecated and, in
     * Spring 6.2.x, compiles to a bare {@code return} - it does nothing at all. Its request type
     * also became a STREAMING one ({@code SimpleClientHttpRequest extends
     * AbstractStreamingClientHttpRequest}), which picks {@code setFixedLengthStreamingMode} vs
     * {@code setChunkedStreamingMode} purely off {@code headers .getContentLength()}. So the fix
     * below is a repair, not just a translation - and the {@code SidecarClientTransportTest}
     * assertions exist so it cannot rot silently a second time.
     *
     * <p>The JDK factory does not have an {@code outputStreaming} switch; it decides per request,
     * in {@code JdkClientHttpRequest.bodyPublisher(...)}: {@code Content-Length > 0} on the
     * outgoing headers selects {@code BodyPublishers.fromPublisher(publisher, contentLength)} - a
     * publisher with a KNOWN length, which the JDK client emits as a real {@code Content-Length}
     * header - while an absent/negative one selects the unknown-length overload, i.e. exactly the
     * chunked encoding we must not send. Jackson's message converter does not compute a content
     * length (its {@code getContentLength} delegates to the base implementation, which returns
     * {@code null}), so on a bare JDK factory these POSTs DO go out chunked - confirmed by
     * measurement, not inference.
     *
     * <p>{@link RequestBufferingClientHttpRequestFactory} is what closes that gap (Spring's own
     * {@code BufferingClientHttpRequestFactory} also buffers the response, and that wrapper pinned
     * carriers under load, so ours buffers the request only). Its wrapper extends {@code
     * AbstractBufferingClientHttpRequest}, which buffers the serialized body and then, verbatim,
     * sets {@code Content-Length} from the buffer's length whenever the header is still unset
     * before delegating; the wrapper copies those headers onto the delegate request. So the JDK
     * request always sees a positive {@code Content-Length} and always takes the known-length
     * branch - verified on the wire for a small JSON body, for a follow-up request on the same
     * keep-alive connection (the exact shape of the original bug), and for a 256KB base64 {@code
     * /extract} payload. The memory profile is no worse than what it replaces: buffering the body
     * to compute a {@code Content-Length} is what {@code HttpURLConnection} did too, so the large
     * base64 {@code /transcribe} and {@code /extract} bodies are not newly duplicated.
     */
    private static RestClient buildClient(String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HTTP_CLIENT);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new RequestBufferingClientHttpRequestFactory(requestFactory))
                .build();
    }

    /**
     * Overload for callers with no model preference - uses the sidecar's own default
     * (EmbeddingModelCatalog.DEFAULT_KEY).
     */
    public SidecarEmbedResponse embed(String text, int chunkSize, int chunkOverlap) {
        return embed(text, chunkSize, chunkOverlap, null);
    }

    /**
     * Multi-model ingestion - {@code embeddingModel} is one of EmbeddingModelCatalog's keys (or
     * null for the sidecar's default). Every embedding in the response is already zero-padded to
     * EmbeddingModelCatalog.STORAGE_DIMENSIONS by the sidecar - see SidecarEmbedResponse's javadoc.
     *
     * <p>Uses the ingest-profile client (5 min read timeout) - this is the whole-document path.
     * {@link #embedQuery} deliberately routes its single-chunk call through the query-profile
     * client instead.
     */
    public SidecarEmbedResponse embed(
            String text, int chunkSize, int chunkOverlap, String embeddingModel) {
        return embeddings.embed(ingestRestClient, text, chunkSize, chunkOverlap, embeddingModel);
    }

    /**
     * Embeds text that {@link Chunker} has ALREADY split, one sidecar {@code /embed} call per
     * chunk, and reassembles the results into the same {@link SidecarEmbedResponse} shape {@link
     * #embed} returns (chunk {@code index} = position in {@code chunks}, {@code text} = the
     * caller's original chunk text with its line structure intact - the sidecar re-joins words with
     * single spaces, which would flatten markdown).
     *
     * <p>Each call passes {@code chunk_size = Chunker.MAX_CHUNK_SIZE} and {@code chunk_overlap =
     * 0}; since {@link Chunker} never emits a chunk longer than that, the sidecar returns exactly
     * one embedding per call - the same single-chunk contract {@link #embedQuery} already relies
     * on. A response with any other chunk count means that contract broke, and fails loudly rather
     * than silently storing a partial-text embedding.
     *
     * <p>One request per chunk rather than one per document: the sidecar has no pre-chunked batch
     * endpoint today (adding one belongs to the sidecar scope). Embedding compute dominates either
     * way; the per-call overhead is a loopback/cluster-local round trip.
     */
    public SidecarEmbedResponse embedPreChunked(List<String> chunks, String embeddingModel) {
        return embeddings.embedPreChunked(ingestRestClient, chunks, embeddingModel);
    }

    /**
     * Optional circuit breaker for the embeddings path ({@code
     * app.resilience.circuit-breaker.enabled}, default true; false gives {@link
     * ResilienceGuard#NOOP}, so nothing changes). Setter injection keeps the constructor - and
     * every test that builds this class by hand - untouched.
     */
    @Autowired(required = false)
    public void setCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
        embeddings.setCircuitBreakerRegistry(registry);
    }

    /**
     * Embeds a single piece of query text and returns its vector.
     *
     * <p>Deliberately reuses /embed rather than calling the sidecar's /embed-query directly:
     * /embed-query is correct and works fine when curl'd directly (confirmed), but the request this
     * client sent it was observed arriving at FastAPI with an empty body ("loc": ["body"], "input":
     * null) - a reproducible interop quirk between this RestClient/ JDK HttpClient setup and that
     * one small single-field endpoint that wasn't worth chasing further under MVP time pressure,
     * when /embed (already proven working end-to-end from this exact client via the ingestion path)
     * trivially covers the same need: a query short enough to be one chunk (the normal case,
     * chunk_overlap=0 so there's only ever one) returns exactly one chunk, and its embedding is the
     * query embedding. /embed-query stays in the sidecar unchanged for direct/ future use - it
     * isn't broken, this just stops depending on it from Java until the root cause is understood.
     */
    public float[] embedQuery(String text) {
        return embedQuery(text, null);
    }

    /**
     * Multi-model ingestion - {@code embeddingModel} must match whichever model the collection(s)
     * being searched were ingested with (see KnowledgeSearchService, which embeds the query once
     * per distinct model among the target collections rather than once overall).
     *
     * <p>Routes through the query-profile client (30s read timeout) - this runs on a live search
     * thread embedding one short query, never a whole document, so the ingest path's minutes-scale
     * allowance would only serve to hold a user's search hostage to a wedged sidecar.
     */
    public float[] embedQuery(String text, String embeddingModel) {
        if (embeddings.hasExternalEmbedder()) {
            // No sidecar round trip and no boxed List<Double> detour: straight float[] to the
            // caller.
            return embeddings.embedQueryVector(text, embeddingModel, QUERY_CHUNK_SIZE);
        }
        SidecarEmbedResponse response =
                embeddings.embed(queryRestClient, text, QUERY_CHUNK_SIZE, 0, embeddingModel);
        List<SidecarChunk> chunks = response.chunks();
        if (chunks.isEmpty()) {
            throw new SidecarCommunicationException(
                    "Sidecar /embed returned no chunks for a query embedding", null);
        }
        List<Double> embedding = chunks.get(0).embedding();
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = embedding.get(i).floatValue();
        }
        return vector;
    }

    /**
     * Knowledge Stage F3a - calls the sidecar's /extract endpoint to turn a PDF/docx/html file's
     * raw bytes into plain text, which callers then feed through {@link #embed} exactly like a .md
     * upload's raw text.
     *
     * <p>Base64-encodes the body rather than sending multipart/form-data - this client already has
     * a documented interop quirk with this exact sidecar over chunked transfer-encoding (see the
     * constructor's javadoc); staying on the already-proven-working JSON-body pattern every other
     * call here uses avoids risking a second, different interop issue with multipart instead.
     *
     * <p>A 4xx response means the file itself was the problem (unsupported extension,
     * corrupt/unparseable content, or no readable text after extraction) - translated to {@link
     * InvalidKnowledgeUploadException} (400, a client problem) rather than {@link
     * SidecarCommunicationException} (502, "the sidecar itself is broken"), which is what any other
     * failure (connection refused, 5xx, timeout) still becomes.
     */
    public String extractText(String filename, byte[] content) {
        return extractText(filename, content, null);
    }

    /**
     * Same as {@link #extractText(String, byte[])}, but threads a tenant's active cloud OCR config
     * through to the sidecar when {@code ocrConfig} is present (OCR provider abstraction) - {@code
     * null} means "no cloud provider configured for this tenant," which the sidecar treats
     * identically to an absent field: fall back to Tesseract.
     */
    public String extractText(String filename, byte[] content, ActiveOcrConfig ocrConfig) {
        if (!sidecarEnabled) {
            // In-process fallback for the formats that need no native libraries (html, docx); PDF
            // and
            // images (OCR) answer "feature unavailable" rather than a bad-gateway error.
            return InProcessTextExtractor.extract(filename, content);
        }
        return conversion.extractText(ingestRestClient, filename, content, ocrConfig);
    }

    /**
     * Knowledge Stage F3c - calls the sidecar's /transcribe endpoint. Only ever called by {@code
     * KnowledgeIngestionJobRunner} (a background scheduled poller), never from a live HTTP request
     * thread - this call can legitimately block for minutes on a real audio file, which is exactly
     * why the async job pattern exists (see {@code KnowledgeIngestionJob}'s javadoc). That used to
     * be the argument for no timeout at all; it is now a bounded-but-very-generous {@link
     * #TRANSCRIBE_READ_TIMEOUT} instead (see the class javadoc) - a background poller thread
     * blocking for the real duration of transcription is fine, a poller thread blocked FOREVER on a
     * dead-but-connected sidecar is a slow resource leak.
     *
     * <p>Same 4xx-vs-everything-else translation as {@link #extractText}: a bad/corrupt/unsupported
     * audio file becomes {@link InvalidKnowledgeUploadException}, anything else becomes {@link
     * SidecarCommunicationException}.
     *
     * <p><b>Not for live request threads.</b> {@link #transcriptionRestClient} carries {@link
     * #TRANSCRIBE_READ_TIMEOUT} (30 minutes) specifically because this method's only intended
     * caller is the background poller described above - a caller on a live HTTP request thread must
     * use {@link #transcribeAudioLive} instead, which is identical except for a read timeout sized
     * for a request a human is actively waiting on.
     */
    public String transcribeAudio(String filename, byte[] content) {
        requireSidecar("Audio/video transcription");
        return conversion.transcribe(transcriptionRestClient, filename, content);
    }

    /**
     * Live-request-thread counterpart to {@link #transcribeAudio}, for exactly one caller: {@code
     * VoiceCallController#turn}'s synchronous sidecar-Whisper fallback, which {@code turn}'s own
     * javadoc documents as running to completion BEFORE that endpoint's SSE response begins - i.e.
     * on a live voice-call request thread, not the background job runner {@link #transcribeAudio}
     * is contracted for. Reusing {@link #transcribeAudio} there would let a stalled sidecar hang a
     * live call for up to {@link #TRANSCRIBE_READ_TIMEOUT} (30 minutes); this method exists solely
     * to give that call site {@link #LIVE_TRANSCRIBE_READ_TIMEOUT} (45s) instead, without shrinking
     * the background job runner's genuinely different, genuinely long-running budget. Same
     * request/response shape, retry behavior, and error translation as {@link #transcribeAudio} -
     * only the underlying {@link RestClient}'s read timeout differs.
     */
    public String transcribeAudioLive(String filename, byte[] content) {
        requireSidecar("Audio/video transcription");
        return conversion.transcribe(liveTranscriptionRestClient, filename, content);
    }

    /**
     * Master-Roadmap-2026.md item #2 (hybrid search + reranking). Scores {@code candidates} against
     * {@code query} with the sidecar's cross-encoder and returns raw scores in the same order they
     * were passed in - see {@code reranking.py}'s module docstring for why this is a separate, more
     * expensive pass than {@link #embedQuery}, meant only for a small post-fusion shortlist, never
     * a full collection scan.
     *
     * <p>Fails open, unlike every other method on this client: a rerank failure (sidecar down, OOM
     * on a cold cross-encoder load, etc.) returns {@code null} rather than throwing, so {@link
     * KnowledgeSearchService} can fall back to its RRF-fused ordering instead of failing the whole
     * search - reranking is a quality improvement on top of a search that already works, not a
     * dependency the search should break without. Runs on live search threads, so it uses the
     * query-profile client (30s read timeout); no connect retry either - falling back to the fused
     * order immediately beats stalling the search a further 250ms.
     */
    public List<Double> rerank(String query, List<String> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!sidecarEnabled) {
            return null; // fail open to the fused order, without a connect attempt per search
        }
        try {
            SidecarRerankResponse response =
                    queryRestClient
                            .post()
                            .uri("/rerank")
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(this::applyInternalToken)
                            .body(new SidecarRerankRequest(query, candidates))
                            .retrieve()
                            .body(SidecarRerankResponse.class);
            return response == null ? null : response.scores();
        } catch (RestClientException e) {
            log.warn(
                    "Sidecar /rerank failed, falling back to fused (non-reranked) order: {}",
                    e.getMessage());
            return null;
        }
    }

    // --- Video pipeline media endpoints (Stage 2/3): see SidecarMediaClient -------

    /** Sarvam narration, one segment per episode beat (sidecar {@code POST /tts}). */
    public SidecarTtsResponse tts(Map<String, Object> request) {
        requireSidecar("Text-to-speech narration");
        return media.tts(request);
    }

    /** Word-level captions as SRT from already-synthesized audio ({@code POST /captions}). */
    public SidecarCaptionsResponse captions(Map<String, Object> request) {
        requireSidecar("Caption generation");
        return media.captions(request);
    }

    /**
     * Queue an ffmpeg render (sidecar {@code POST /render}, answers 202); poll {@link
     * #renderStatus}.
     */
    public SidecarRenderJobResponse renderSubmit(Map<String, Object> request) {
        requireSidecar("Video rendering");
        return media.renderSubmit(request);
    }

    /** Read a render job's state (sidecar {@code GET /render/{jobId}}). */
    public SidecarRenderJobResponse renderStatus(String jobId) {
        requireSidecar("Video rendering");
        return media.renderStatus(jobId);
    }
}
