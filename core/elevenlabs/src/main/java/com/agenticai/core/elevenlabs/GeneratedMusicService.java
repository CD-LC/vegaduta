package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.storage.FileStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores/retrieves {@link GeneratedMusic} rows via {@link FileStorageService} - mirrors {@link ElevenLabsGeneratedAudioService}. */
@Service
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class GeneratedMusicService {

    private static final String MP3_CONTENT_TYPE = "audio/mpeg";
    private static final int MAX_STORED_PROMPT_CHARS = 2000;

    private final GeneratedMusicRepository repository;
    private final FileStorageService fileStorageService;

    public GeneratedMusicService(GeneratedMusicRepository repository, FileStorageService fileStorageService) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    /** @return the new row's id - {@code GenerateMusicToolCallback} turns this into a {@code /api/musicgen/tracks/{id}} reference. */
    @Transactional
    public UUID store(byte[] mp3Bytes, String prompt, String model, String provider, String billedTo, int durationSeconds) {
        String objectKey = "generated-music/" + UUID.randomUUID() + ".mp3";
        fileStorageService.put(objectKey, mp3Bytes, MP3_CONTENT_TYPE);
        String value = prompt == null ? "" : prompt;
        String storedPrompt = value.length() > MAX_STORED_PROMPT_CHARS ? value.substring(0, MAX_STORED_PROMPT_CHARS) : value;
        GeneratedMusic track = GeneratedMusic.create(
            "music.mp3", MP3_CONTENT_TYPE, objectKey, storedPrompt, model, provider, billedTo, durationSeconds);
        return repository.save(track).getId();
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedMusic> find(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<GeneratedMusic> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** Tracks the PLATFORM key paid for since {@code after} - the quota ledger, see {@link GeneratedMusicRepository}. */
    @Transactional(readOnly = true)
    public long countPlatformBilledSince(Instant after) {
        return repository.countByBilledToAndCreatedAtAfter(GeneratedMusic.BILLED_TO_PLATFORM, after);
    }
}
