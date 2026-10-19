package com.agenticai.core.sarvam;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.sarvam.dto.SarvamGeneratedAudioResponse;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
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

/**
 * Serves back a {@link SarvamGeneratedAudio} row by id - what {@code
 * SarvamSpeakToolCallback}'s {@code audioUrl} reference actually resolves
 * to. Same shape as {@code WorkflowController#downloadRunOutput}: an
 * ordinary authenticated GET (tenant isolation comes from schema-per-tenant
 * resolution the same way every other endpoint gets it - no bespoke access
 * check needed here), decode-and-stream with a {@code Content-Disposition:
 * attachment} header so a browser or the console's own fetch-then-blob-URL
 * pattern downloads it as a real file rather than rendering inline.
 *
 * <p>Storage migration Stage 3: rows created since migration V65 carry an
 * {@code objectKey} and are streamed from {@link FileStorageService}; rows
 * created before it have no objectKey and keep serving via the legacy
 * base64 decode path - see {@link SarvamGeneratedAudio}'s javadoc.
 */
@RestController
@RequestMapping("/api/sarvam/audio")
public class SarvamGeneratedAudioController {

    private final SarvamGeneratedAudioService service;
    private final FileStorageService fileStorageService;

    public SarvamGeneratedAudioController(
        SarvamGeneratedAudioService service,
        FileStorageService fileStorageService
    ) {
        this.service = service;
        this.fileStorageService = fileStorageService;
    }

    /** Backs the Files page's "Generated speech (Sarvam)" table - see {@code SarvamGeneratedAudioService#listAll}. */
    @GetMapping
    public ResponseEntity<List<SarvamGeneratedAudioResponse>> list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        // No limit = the complete list, as before; limit 1..200 and offset page it newest first.
        int size = ListPaging.limit(limit, 50, 200);
        long skip = ListPaging.offset(offset);
        return ListPaging.respond(
            service.listPage(skip, size).stream().map(SarvamGeneratedAudioResponse::from).toList(),
            size, skip, service::countAll);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return service.find(id)
            .map(audio -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    AttachmentDisposition.attachment(audio.getFilename()))
                .body(resolveBytes(audio)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private byte[] resolveBytes(SarvamGeneratedAudio audio) {
        if (audio.getObjectKey() != null) {
            try (InputStream in = fileStorageService.get(audio.getObjectKey())) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new com.agenticai.core.storage.FileStorageException(
                    "Could not read object '" + audio.getObjectKey() + "'", e);
            }
        }
        return Base64.getDecoder().decode(audio.getAudioDataBase64());
    }
}
