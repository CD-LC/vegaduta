package com.agenticai.core.elevenlabs;

import com.agenticai.core.elevenlabs.dto.ElevenLabsVoiceResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Voice cloning setup UI's backend - upload a sample clip, get a cloned voice back. A real
 * multipart upload endpoint rather than a tool call - see {@code core/elevenlabs} module's javadoc
 * for why. Mirrors the shape of {@code ChatAttachmentService}'s multipart handling ({@code
 * MultipartFile} in, bytes out) already established in {@code core/agents}.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
@RequestMapping("/api/elevenlabs/voices")
public class ElevenLabsVoiceController {

    private final ElevenLabsVoiceService voiceService;

    public ElevenLabsVoiceController(ElevenLabsVoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @PostMapping
    public ResponseEntity<ElevenLabsVoiceResponse> clone(
            @RequestParam("name") String name, @RequestParam("file") MultipartFile file) {
        try {
            ElevenLabsVoice voice =
                    voiceService.cloneVoice(
                            name,
                            file.getBytes(),
                            file.getContentType(),
                            file.getOriginalFilename());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ElevenLabsVoiceResponse.from(voice));
        } catch (IOException e) {
            throw new ElevenLabsApiException(
                    0, "Could not read the uploaded sample file: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<List<ElevenLabsVoiceResponse>> list() {
        return ResponseEntity.ok(
                voiceService.list().stream().map(ElevenLabsVoiceResponse::from).toList());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        voiceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
