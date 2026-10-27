package com.agenticai.core.knowledge;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The optional Docling-first document conversion route ({@code app.knowledge.docling.*}), one of
 * the three integrations from the 2026-10 OSS-integration review (Docling for ingestion quality,
 * Composio for SaaS tools, MCP-registry/n8n for ecosystem reach).
 *
 * <p><b>Why a separate service at all.</b> {@code services/docling} is a thin FastAPI adapter
 * around IBM's Docling parser (layout-aware PDF, table structure, OCR, and Office formats the
 * sidecar never handled). It deliberately speaks the sidecar's EXACT {@code /extract} contract -
 * {@code {filename, content_base64} -> {text}} with {@code X-Internal-Token} auth - so this class
 * needs no wire code of its own: {@link SidecarConversionClient} does the request, error
 * translation (4xx = the file's fault -&gt; {@link InvalidKnowledgeUploadException}, everything
 * else -&gt; {@link SidecarCommunicationException}), and connect-retry; {@link
 * SidecarClient#buildClient} supplies the already-audited transport (JDK HttpClient for
 * virtual-thread safety, real {@code Content-Length}, the ingest-profile read timeout). The only
 * thing genuinely new here is the routing decision: which files go to Docling, and what happens
 * when it is down.
 *
 * <p><b>Loose coupling, off by default.</b> With {@code base-url} unset this bean is inert -
 * {@link #handles} answers false for everything and {@code SidecarClient#extractText} takes the
 * unchanged sidecar path. Docling's model weight (layout/OCR models, several GB) never becomes a
 * cost every deployment pays; it is a compose profile ({@code docling}) plus one env var, and a
 * deployment can even run Docling with the sidecar disabled since this route is checked before
 * the sidecar gate.
 *
 * <p><b>Failure policy.</b> A Docling transport/5xx failure falls back to the sidecar's extractor
 * ({@code fallback-to-sidecar}, default true) so enabling the better parser never makes ingestion
 * LESS reliable than the status quo. A 4xx means the file itself was rejected - that propagates
 * as-is, because replaying the same bytes against a weaker parser would only produce a worse
 * result or the same 400.
 */
@Component
@Profile("knowledge")
public class DoclingConversionService {

    /**
     * Every extension {@code services/docling/app/conversion.py}'s {@code SUPPORTED_EXTENSIONS}
     * accepts - the two lists MUST move together (the Java side pre-filters before ever sending
     * bytes, so a filename this set accepts but the service rejects surfaces as a 400 that used to
     * be a clean pre-flight rejection).
     */
    static final Set<String> EXTENSIONS =
            Set.of(
                    ".pdf", ".docx", ".pptx", ".xlsx", ".html", ".htm", ".csv",
                    ".png", ".jpg", ".jpeg", ".webp", ".tiff", ".tif", ".bmp");

    /**
     * The subset of {@link #EXTENSIONS} the sidecar's extractor does NOT handle - these widen the
     * upload allowlist ({@code KnowledgeController#SUPPORTED_EXTENSIONS}) only while Docling is
     * enabled, so a deployment without the service still rejects them up front rather than letting
     * them reach the sidecar and 400 there.
     */
    private static final Set<String> EXTRA_EXTENSIONS =
            Set.of(".pptx", ".xlsx", ".csv", ".tiff", ".tif", ".bmp");

    private final boolean enabled;
    private final boolean fallbackToSidecar;
    private final String sharedSecret;

    /** Null while disabled - never built against an empty base URL. */
    private final RestClient restClient;

    /**
     * Reused verbatim: Docling speaks the same {@code /extract} JSON contract, so the request
     * building, 4xx-vs-5xx translation, and connect-retry the sidecar path already carries apply
     * unchanged - only the {@link RestClient} (base URL) and the auth header value differ.
     */
    private final SidecarConversionClient conversion;

    @Autowired
    public DoclingConversionService(
            @Value("${app.knowledge.docling.base-url:}") String baseUrl,
            @Value("${app.knowledge.docling.shared-secret:${DOCLING_SHARED_SECRET:}}") String sharedSecret,
            @Value("${app.knowledge.docling.fallback-to-sidecar:true}") boolean fallbackToSidecar) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.fallbackToSidecar = fallbackToSidecar;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        this.restClient = enabled ? SidecarClient.buildClient(baseUrl.trim(), SidecarClient.INGEST_READ_TIMEOUT) : null;
        this.conversion = new SidecarConversionClient(this::applyInternalToken);
    }

    /** True only while {@code app.knowledge.docling.base-url} is configured. */
    public boolean enabled() {
        return enabled;
    }

    /** Whether a Docling failure may fall back to the sidecar's extractor. Default true. */
    public boolean fallbackToSidecar() {
        return fallbackToSidecar;
    }

    /**
     * Whether this filename should route to Docling - the extension allowlist AND the enabled flag
     * together, so callers need no separate "is docling on" check.
     */
    public boolean handles(String filename) {
        if (!enabled || filename == null) {
            return false;
        }
        String lowered = filename.toLowerCase(java.util.Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lowered::endsWith);
    }

    /**
     * Extensions Docling adds on top of the sidecar's allowlist (empty when disabled) - merged into
     * {@code KnowledgeController}'s upload validation.
     */
    public Set<String> extraExtensions() {
        return enabled ? EXTRA_EXTENSIONS : Set.of();
    }

    /** The extension list shown in upload-rejection messages when Docling is enabled. */
    public List<String> extraExtensionsSorted() {
        return extraExtensions().stream().sorted().toList();
    }

    /**
     * Runs the file through Docling's {@code /extract} and returns its markdown output. Callers
     * must check {@link #handles} first; a tenant's cloud-OCR config is deliberately not threaded
     * (Docling has its own OCR stack - the field exists on the wire contract for compatibility
     * and is sent unset).
     */
    public String extract(String filename, byte[] content) {
        return conversion.extractText(restClient, filename, content, null);
    }

    /** Same absent-vs-present header rule as {@code SidecarClient#applyInternalToken}. */
    private void applyInternalToken(HttpHeaders headers) {
        if (!sharedSecret.isEmpty()) {
            headers.set("X-Internal-Token", sharedSecret);
        }
    }
}
