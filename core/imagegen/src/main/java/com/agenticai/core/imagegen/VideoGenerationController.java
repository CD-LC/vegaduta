package com.agenticai.core.imagegen;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.imagegen.dto.VideoGenerationJobResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Authenticated video generation: {@code POST /api/videogen/generate}
 * enqueues (quota/plan-gated - see {@link VideoGenerationQuotaService}),
 * {@code GET /api/videogen/jobs/{id}} polls, {@code GET
 * /api/videogen/videos/{id}} streams the finished result once {@code
 * COMPLETE}. Same three-endpoint shape {@link GeneratedImageController}/
 * {@code KnowledgeIngestionJobController} already use, combined for video's
 * async-job case.
 *
 * <h2>{@code /videos/{id}} streams, with real Range support</h2>
 * The video is served via {@link StreamingResponseBody} rather than
 * buffered into a {@code byte[]} first - each concurrent download used to
 * cost its full file size in heap on a virtual-thread request (up to 60s
 * of generated video, see {@link VideoGenerationRequest#durationSeconds}),
 * which is exactly the load the platform is trying to keep off the JVM.
 *
 * <p>{@code Accept-Ranges: bytes} is advertised, and - now that {@link
 * FileStorageService#size} exists (edge-capabilities plan item X-2,
 * 2026-08-04) - a single range in ANY of RFC 7233's three single-range
 * forms is honoured with a real 206 carrying a correct {@code
 * Content-Range: bytes start-end/total}: fully bounded ({@code
 * bytes=0-499}, with an end past EOF clamped to {@code total-1} as §2.1
 * requires), open-ended ({@code bytes=500-}, the form browsers actually
 * send when a viewer seeks), and suffix ({@code bytes=-500}). The bytes are
 * pulled via {@link FileStorageService#getRange} - a ranged S3 read, so
 * only the requested window crosses the wire, rather than streaming from
 * byte 0 and discarding. An unsatisfiable range (start at/past EOF, or a
 * zero-length suffix) gets the RFC-mandated 416 with a {@code
 * Content-Range} of {@code bytes *&#47;total}.
 *
 * <p>Two deliberate fallbacks to a full 200 remain, both explicitly
 * permitted by RFC 7233 §3.1 ("a server ... MAY ignore the Range header
 * field"): <b>multi-range requests</b> ({@code bytes=0-99,200-299} -
 * {@code multipart/byteranges} assembly is real complexity no video
 * element ever needs; none of them send multi-range), and <b>a failed
 * size lookup</b> (object storage briefly unreachable) - in that case the
 * old pre-{@code size()} behaviour is kept: a fully-bounded range is still
 * served as a 206 with {@code Content-Range: .../*} via {@link
 * #parseBoundedRange}, anything else streams as a 200.
 */
@RestController
@RequestMapping("/api/videogen")
public class VideoGenerationController {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationController.class);

    private final VideoGenerationJobService jobService;
    private final FileStorageService fileStorageService;

    public VideoGenerationController(VideoGenerationJobService jobService, FileStorageService fileStorageService) {
        this.jobService = jobService;
        this.fileStorageService = fileStorageService;
    }

    /**
     * @param imageBase64 optional uploaded photo (already base64-encoded) to animate FROM -
     *     image-to-video generation, the mechanism used to keep the clip close to the photo's
     *     actual likeness rather than reinterpreting the scene from the prompt alone. {@code
     *     null}/blank means text-only generation. Size cap is a generous-but-bounded ~9 MB raw
     *     image (base64 runs ~4/3 the raw byte count) - Veo clips are short, a bigger source
     *     photo buys nothing and just risks a slow/rejected request.
     * @param imageMimeType required alongside {@code imageBase64} when present; defaults to
     *     {@code image/jpeg} if the caller omits it while still sending image bytes.
     */
    public record VideoGenerationRequest(
        @NotBlank @Size(max = 1000) String prompt,
        @Min(1) @Max(60) int durationSeconds,
        @Size(max = 12_000_000) String imageBase64,
        @Size(max = 100) String imageMimeType
    ) {
    }

    @PostMapping("/generate")
    public VideoGenerationJobResponse generate(@Valid @RequestBody VideoGenerationRequest request) {
        VideoGenerationJob job = jobService.enqueue(
            request.prompt().trim(), request.durationSeconds(), toSourceImage(request));
        return VideoGenerationJobResponse.from(job);
    }

    private static VideoGenerationClient.SourceImage toSourceImage(VideoGenerationRequest request) {
        if (request.imageBase64() == null || request.imageBase64().isBlank()) {
            return null;
        }
        String mimeType = request.imageMimeType() == null || request.imageMimeType().isBlank()
            ? "image/jpeg"
            : request.imageMimeType();
        return new VideoGenerationClient.SourceImage(request.imageBase64(), mimeType);
    }

    @GetMapping("/jobs/{id}")
    public VideoGenerationJobResponse getJob(@PathVariable UUID id) {
        return VideoGenerationJobResponse.from(jobService.get(id));
    }

    /** Backs the Files page's "Generated video" table - every job regardless of status, newest first; {@code videoUrl} is only populated once {@code COMPLETE} (see {@link VideoGenerationJobResponse#from}). */
    @GetMapping("/jobs")
    public ResponseEntity<List<VideoGenerationJobResponse>> listJobs(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        // No limit = the complete list, as before; limit 1..200 and offset page it newest first.
        int size = ListPaging.limit(limit, 50, 200);
        long skip = ListPaging.offset(offset);
        return ListPaging.respond(
            jobService.listPage(skip, size).stream().map(VideoGenerationJobResponse::from).toList(),
            size, skip, jobService::countAll);
    }

    @GetMapping("/videos/{id}")
    public ResponseEntity<StreamingResponseBody> getVideo(
        @PathVariable UUID id,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        VideoGenerationJob job = jobService.get(id);
        if (job.getStatus() != VideoGenerationJobStatus.COMPLETE || job.getResultObjectKey() == null) {
            return ResponseEntity.notFound().build();
        }

        String objectKey = job.getResultObjectKey();
        MediaType contentType = MediaType.parseMediaType(job.getResultContentType());
        // TenantContext is a plain ThreadLocal set by the security filter on
        // THIS (original request) thread. Spring dispatches the
        // StreamingResponseBody callbacks below asynchronously on a separate
        // thread, where it would otherwise be empty - making
        // fileStorageService.get resolve the unprefixed physical key instead
        // of the caller's tenant/<id>/... one and 404. Capture it here, then
        // re-populate it for each callback's duration. (size() below runs on
        // THIS thread, so it sees the filter-set context directly.)
        TenantContext capturedContext = TenantContext.current().orElse(null);

        Long totalLength = null;
        try {
            totalLength = fileStorageService.size(objectKey);
        } catch (FileStorageException e) {
            // Storage briefly unreachable (or the object gone mid-flight) -
            // fall back to the pre-size() behaviour below rather than turning
            // a COMPLETE job's video into a hard error here; a genuinely
            // missing object still fails when the body callback runs, same
            // as before this change.
            log.warn("Could not determine size of video object for job {} - serving without full Range support", id, e);
        }

        if (totalLength == null) {
            long[] boundedRange = parseBoundedRange(rangeHeader);
            if (boundedRange == null) {
                return fullResponse(contentType, null, objectKey, capturedContext);
            }
            return partialResponse(
                contentType, boundedRange[0], boundedRange[1], "*", objectKey, capturedContext);
        }

        long total = totalLength;
        ByteRange range = resolveRange(rangeHeader, total);
        if (range == null) {
            return fullResponse(contentType, total, objectKey, capturedContext);
        }
        if (range == ByteRange.UNSATISFIABLE) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                .build();
        }
        return partialResponse(
            contentType, range.start(), range.end(), Long.toString(total), objectKey, capturedContext);
    }

    private ResponseEntity<StreamingResponseBody> fullResponse(
        MediaType contentType, Long totalLength, String objectKey, TenantContext capturedContext
    ) {
        StreamingResponseBody body = outputStream -> {
            TenantContext.set(capturedContext);
            try (InputStream in = fileStorageService.get(objectKey)) {
                in.transferTo(outputStream);
            } finally {
                TenantContext.clear();
            }
        };
        var response = ResponseEntity.ok()
            .contentType(contentType)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable());
        if (totalLength != null) {
            response = response.contentLength(totalLength);
        }
        return response.body(body);
    }

    /**
     * @param totalForContentRange either the object's total length as a
     *     string, or {@code "*"} for the size-lookup-failed fallback path
     *     (RFC 7233 §4.2 allows an unknown complete length only as {@code *})
     */
    private ResponseEntity<StreamingResponseBody> partialResponse(
        MediaType contentType, long start, long end, String totalForContentRange,
        String objectKey, TenantContext capturedContext
    ) {
        // "*" means size() failed, so a ranged storage read may not be
        // satisfiable either - keep the old full-get + copyRange there.
        // With a known total the window comes from a ranged S3 read: only
        // (end - start + 1) bytes cross the wire, nothing is skipped.
        boolean sizeKnown = !"*".equals(totalForContentRange);
        StreamingResponseBody body = outputStream -> {
            TenantContext.set(capturedContext);
            try {
                if (sizeKnown) {
                    try (InputStream in = fileStorageService.getRange(objectKey, start, end)) {
                        in.transferTo(outputStream);
                    }
                } else {
                    try (InputStream in = fileStorageService.get(objectKey)) {
                        // StreamUtils.copyRange: skips `start` bytes, then
                        // copies (end - start + 1) bytes through a fixed-size
                        // internal buffer (never the whole file at once).
                        StreamUtils.copyRange(in, outputStream, start, end);
                    }
                }
            } finally {
                TenantContext.clear();
            }
        };
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
            .contentType(contentType)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalForContentRange)
            .contentLength(end - start + 1)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
            .body(body);
    }

    /**
     * A resolved, satisfiable single byte range - both bounds inclusive,
     * already clamped/validated against the object's total length. The
     * {@link #UNSATISFIABLE} sentinel (compared by identity) is {@link
     * VideoGenerationController#resolveRange}'s "valid Range syntax, but
     * nothing in it can be served" outcome, which HTTP maps to 416 rather
     * than either a 206 or a fallback 200.
     */
    record ByteRange(long start, long end) {
        static final ByteRange UNSATISFIABLE = new ByteRange(-1, -1);
    }

    /**
     * Resolves a {@code Range} request header against a known total object
     * length into the one of RFC 7233's three single-range forms it
     * expresses. Package-private for direct unit testing.
     *
     * @return {@code null} when there is no honourable range and the caller
     *     should serve a full 200 (no header, malformed syntax, or a
     *     multi-range request - see the class javadoc for why multi-range
     *     deliberately falls back); {@link ByteRange#UNSATISFIABLE} when the
     *     syntax is valid but no byte can be served (416); otherwise the
     *     clamped, inclusive range to serve as a 206
     */
    static ByteRange resolveRange(String rangeHeader, long totalLength) {
        if (rangeHeader == null || totalLength < 0) {
            return null;
        }
        String value = rangeHeader.trim();
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            if (dash == 0) {
                // Suffix range "-N": the last N bytes. N = 0 is explicitly
                // unsatisfiable per RFC 7233 §2.1; N > total means the whole
                // object.
                long suffixLength = Long.parseLong(spec.substring(1));
                if (suffixLength == 0 || totalLength == 0) {
                    return ByteRange.UNSATISFIABLE;
                }
                long start = Math.max(0, totalLength - suffixLength);
                return new ByteRange(start, totalLength - 1);
            }
            long start = Long.parseLong(spec.substring(0, dash));
            if (start >= totalLength) {
                // Includes the totalLength == 0 case - an empty object has no
                // satisfiable non-suffix range at all.
                return ByteRange.UNSATISFIABLE;
            }
            if (dash == spec.length() - 1) {
                // Open-ended "start-": from start to EOF.
                return new ByteRange(start, totalLength - 1);
            }
            long end = Long.parseLong(spec.substring(dash + 1));
            if (end < start) {
                return null;
            }
            // An end past EOF is not an error - §2.1 says to serve to EOF.
            return new ByteRange(start, Math.min(end, totalLength - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a single, fully-bounded byte range ({@code "bytes=start-end"})
     * out of a {@code Range} request header. Returns {@code null} for
     * anything else. Since {@link #resolveRange} landed this is only the
     * degraded path for when {@link FileStorageService#size} fails (see the
     * class javadoc) - without a total length, a bounded range is the one
     * form still expressible (with {@code Content-Range: .../*}).
     * Package-private for direct unit testing.
     */
    static long[] parseBoundedRange(String rangeHeader) {
        if (rangeHeader == null) {
            return null;
        }
        String value = rangeHeader.trim();
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring("bytes=".length());
        int dash = spec.indexOf('-');
        if (dash <= 0 || dash == spec.length() - 1) {
            // No dash, a suffix range ("-500"), or an open-ended range
            // ("500-") - none resolvable without the object's total length.
            return null;
        }
        try {
            long start = Long.parseLong(spec.substring(0, dash));
            long end = Long.parseLong(spec.substring(dash + 1));
            if (start < 0 || end < start) {
                return null;
            }
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
