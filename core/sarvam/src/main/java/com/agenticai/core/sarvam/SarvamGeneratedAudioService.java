package com.agenticai.core.sarvam;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores/retrieves {@link SarvamGeneratedAudio} rows - see that class's
 * javadoc for why this table exists instead of reusing the Workflow
 * Designer's Output-node file storage directly.
 *
 * <p>Storage migration Stage 3: {@link #store} now uploads bytes to {@link
 * FileStorageService} (S3-compatible object storage) and persists only the
 * object key, instead of base64-encoding into Postgres - see migration V65.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamGeneratedAudioService {

    private static final String WAV_CONTENT_TYPE = "audio/wav";

    private final SarvamGeneratedAudioRepository repository;
    private final FileStorageService fileStorageService;

    public SarvamGeneratedAudioService(
        SarvamGeneratedAudioRepository repository,
        FileStorageService fileStorageService
    ) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    /** @return the new row's id - the caller (e.g. {@code SarvamSpeakToolCallback}) turns this into a {@code /api/sarvam/audio/{id}} reference. */
    @Transactional
    public UUID store(byte[] wavBytes, String filename) {
        String resolvedFilename = (filename == null || filename.isBlank()) ? "speech.wav" : filename;
        String objectKey = "sarvam-audio/" + UUID.randomUUID() + ".wav";
        fileStorageService.put(objectKey, wavBytes, WAV_CONTENT_TYPE);
        SarvamGeneratedAudio audio = SarvamGeneratedAudio.forObjectStorage(resolvedFilename, WAV_CONTENT_TYPE, objectKey);
        return repository.save(audio).getId();
    }

    @Transactional(readOnly = true)
    public Optional<SarvamGeneratedAudio> find(UUID id) {
        return repository.findById(id);
    }

    /** Count of audio rows generated after {@code since} in the current tenant's schema - backs {@code AudioGenerationQuotaService}'s platform-audio cap. */
    @Transactional(readOnly = true)
    public long countSince(java.time.Instant since) {
        return repository.countByCreatedAtAfter(since);
    }

    /** Backs the Files page's "Generated speech (Sarvam)" listing, newest first. */
    @Transactional(readOnly = true)
    public List<SarvamGeneratedAudio> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** One newest-first window for the paged Files-page listing. */
    @Transactional(readOnly = true)
    public List<SarvamGeneratedAudio> listPage(long offset, int limit) {
        return repository.findAllByOrderByCreatedAtDescIdDesc(new OffsetLimitRequest(offset, limit));
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return repository.count();
    }
}
