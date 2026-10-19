package com.agenticai.core.elevenlabs;

import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.elevenlabs.dto.ElevenLabsGeneratedAudioResponse;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
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

/** Serves back an {@link ElevenLabsGeneratedAudio} row by id - what {@code ElevenLabsSpeakToolCallback}'s {@code audioUrl} reference resolves to. Mirrors {@code SarvamGeneratedAudioController}/{@code GeneratedImageController}. */
@RestController
@RequestMapping("/api/elevenlabs/audio")
public class ElevenLabsGeneratedAudioController {

    private final ElevenLabsGeneratedAudioService service;
    private final FileStorageService fileStorageService;

    public ElevenLabsGeneratedAudioController(
        ElevenLabsGeneratedAudioService service,
        FileStorageService fileStorageService
    ) {
        this.service = service;
        this.fileStorageService = fileStorageService;
    }

    /** Backs the Files page's "Generated speech (ElevenLabs)" table - see {@code ElevenLabsGeneratedAudioService#listAll}. */
    @GetMapping
    public ResponseEntity<List<ElevenLabsGeneratedAudioResponse>> list(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) Long offset
    ) {
        // No limit = the complete list, as before; limit 1..200 and offset page it newest first.
        int size = ListPaging.limit(limit, 50, 200);
        long skip = ListPaging.offset(offset);
        return ListPaging.respond(
            service.listPage(skip, size).stream().map(ElevenLabsGeneratedAudioResponse::from).toList(),
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

    private byte[] resolveBytes(ElevenLabsGeneratedAudio audio) {
        try (InputStream in = fileStorageService.get(audio.getObjectKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new FileStorageException("Could not read object '" + audio.getObjectKey() + "'", e);
        }
    }
}
