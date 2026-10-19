package com.agenticai.core.imagegen;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.imagegen.dto.GeneratedImageResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import com.agenticai.core.security.http.AttachmentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Serves back a {@link GeneratedImage} row by id - what {@code
 * GenerateImageToolCallback}'s {@code imageUrl} reference resolves to.
 * Mirrors {@code com.agenticai.core.sarvam.SarvamGeneratedAudioController}:
 * an ordinary authenticated GET (schema-per-tenant resolution already scopes
 * this, no bespoke access check needed), stream-from-object-storage with a
 * {@code Content-Disposition: attachment} header.
 *
 * <p>Streams via {@link StreamingResponseBody} rather than buffering the
 * whole image into a {@code byte[]} first - the same fix applied to {@link
 * VideoGenerationController}, for the same reason (a heap cost per
 * concurrent download, on a virtual-thread request, is exactly the kind of
 * load the platform is trying to keep off the JVM). Images are smaller and
 * bounded by nature, so the risk here was lower than the video path's, but
 * the fix is one line different and keeps both download endpoints
 * consistent.
 */
@RestController
@RequestMapping("/api/imagegen/images")
public class GeneratedImageController {

    private final GeneratedImageService service;
    private final FileStorageService fileStorageService;

    public GeneratedImageController(
        GeneratedImageService service,
        FileStorageService fileStorageService
    ) {
        this.service = service;
        this.fileStorageService = fileStorageService;
    }

    /** Backs the Files page's "Generated images" table - see {@code GeneratedImageService#listAll}. */
    @GetMapping
    public ResponseEntity<List<GeneratedImageResponse>> list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        // No limit = the complete list, as before; limit 1..200 and offset page it newest first.
        int size = ListPaging.limit(limit, 50, 200);
        long skip = ListPaging.offset(offset);
        return ListPaging.respond(
            service.listPage(skip, size).stream().map(GeneratedImageResponse::from).toList(),
            size, skip, service::countAll);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        return service.find(id)
            .map(image -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    AttachmentDisposition.attachment(image.getFilename()))
                .body(streamBody(image)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Note on error behaviour: since the 200 status and headers above are
     * already committed by the time this callback runs, an
     * {@code IOException} thrown here (a storage read failure mid-stream)
     * can no longer become a clean error response - Spring's async
     * dispatch aborts/resets the connection instead. That is an inherent
     * trade-off of streaming instead of buffer-then-respond, not a
     * regression introduced here; the alternative (reading the whole
     * object first so failures surface before any bytes are sent) is
     * exactly the heap cost this change removes.
     *
     * <p>{@link TenantContext} is a plain {@link ThreadLocal}, populated by
     * the security filter on the original request thread. Spring dispatches
     * this {@link StreamingResponseBody} callback asynchronously on a
     * separate (virtual) thread, where that ThreadLocal is empty - so
     * {@code fileStorageService.get} would silently resolve the UNPREFIXED
     * physical key instead of the caller's {@code tenant/<id>/...} one,
     * 404ing on every object actually written under a tenant prefix. Capture
     * the context on the correct thread here, then re-populate it for the
     * async callback's duration.
     */
    private StreamingResponseBody streamBody(GeneratedImage image) {
        TenantContext capturedContext = TenantContext.current().orElse(null);
        return outputStream -> {
            TenantContext.set(capturedContext);
            try (InputStream in = fileStorageService.get(image.getObjectKey())) {
                in.transferTo(outputStream);
            } finally {
                TenantContext.clear();
            }
        };
    }
}
