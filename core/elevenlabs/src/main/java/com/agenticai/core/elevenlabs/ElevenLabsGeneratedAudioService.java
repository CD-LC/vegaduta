package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores/retrieves {@link ElevenLabsGeneratedAudio} rows via {@link FileStorageService} - mirrors {@code SarvamGeneratedAudioService}/{@code GeneratedImageService} exactly. */
@Service
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class ElevenLabsGeneratedAudioService {

    private static final String MP3_CONTENT_TYPE = "audio/mpeg";

    private final ElevenLabsGeneratedAudioRepository repository;
    private final FileStorageService fileStorageService;

    public ElevenLabsGeneratedAudioService(
        ElevenLabsGeneratedAudioRepository repository,
        FileStorageService fileStorageService
    ) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    /** @return the new row's id - the caller ({@code ElevenLabsSpeakToolCallback}) turns this into a {@code /api/elevenlabs/audio/{id}} reference. */
    @Transactional
    public UUID store(byte[] mp3Bytes, String voiceId) {
        String objectKey = "elevenlabs-audio/" + UUID.randomUUID() + ".mp3";
        fileStorageService.put(objectKey, mp3Bytes, MP3_CONTENT_TYPE);
        ElevenLabsGeneratedAudio audio = ElevenLabsGeneratedAudio.create("speech.mp3", MP3_CONTENT_TYPE, objectKey, voiceId);
        return repository.save(audio).getId();
    }

    @Transactional(readOnly = true)
    public Optional<ElevenLabsGeneratedAudio> find(UUID id) {
        return repository.findById(id);
    }

    /** Backs the Files page's "Generated speech (ElevenLabs)" listing, newest first. */
    @Transactional(readOnly = true)
    public List<ElevenLabsGeneratedAudio> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** One newest-first window for the paged Files-page listing. */
    @Transactional(readOnly = true)
    public List<ElevenLabsGeneratedAudio> listPage(long offset, int limit) {
        return repository.findAllByOrderByCreatedAtDescIdDesc(new OffsetLimitRequest(offset, limit));
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return repository.count();
    }

    /** Usage ledger for {@code ElevenLabsGenerationQuotaService}'s platform-spend cap - same "the tenant schema's row count IS the platform-spend count" reasoning {@code SarvamGeneratedAudioService#countSince} documents, valid only because the platform-fallback path only fires when the tenant has no BYOK ElevenLabs credential. */
    @Transactional(readOnly = true)
    public long countSince(Instant after) {
        return repository.countByCreatedAtAfter(after);
    }
}
