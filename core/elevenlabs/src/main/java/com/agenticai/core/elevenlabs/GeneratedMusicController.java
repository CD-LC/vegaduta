package com.agenticai.core.elevenlabs;

import com.agenticai.core.elevenlabs.dto.GeneratedMusicResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.http.AttachmentDisposition;
import com.agenticai.core.storage.FileStorageException;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.ListPaging;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code generate_music} tracks - authenticated and tenant-schema scoped, same shape as
 * {@link ElevenLabsGeneratedAudioController}.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
@RequestMapping("/api/musicgen/tracks")
public class GeneratedMusicController {

    private final GeneratedMusicService service;
    private final FileStorageService fileStorageService;

    public GeneratedMusicController(
            GeneratedMusicService service, FileStorageService fileStorageService) {
        this.service = service;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public ResponseEntity<List<GeneratedMusicResponse>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(
                service.listAll().stream().map(GeneratedMusicResponse::from).toList(),
                limit,
                offset,
                50,
                200);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return service.find(id)
                .map(
                        track ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(track.getContentType()))
                                        .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                AttachmentDisposition.attachment(
                                                        track.getFilename()))
                                        .body(resolveBytes(track)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private byte[] resolveBytes(GeneratedMusic track) {
        try (InputStream in = fileStorageService.get(track.getObjectKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new FileStorageException(
                    "Could not read object '" + track.getObjectKey() + "'", e);
        }
    }
}
