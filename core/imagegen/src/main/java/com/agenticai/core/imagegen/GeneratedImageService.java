package com.agenticai.core.imagegen;

import com.agenticai.core.tenancy.OffsetLimitRequest;
import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores/retrieves {@link GeneratedImage} rows - mirrors {@code
 * com.agenticai.core.sarvam.SarvamGeneratedAudioService} exactly: {@link
 * #store} uploads bytes to {@link FileStorageService} and persists only the
 * object key, never the raw bytes in Postgres.
 */
@Service
public class GeneratedImageService {

    private final GeneratedImageRepository repository;
    private final FileStorageService fileStorageService;

    public GeneratedImageService(
        GeneratedImageRepository repository,
        FileStorageService fileStorageService
    ) {
        this.repository = repository;
        this.fileStorageService = fileStorageService;
    }

    /** @return the new row's id - the caller ({@code GenerateImageToolCallback}) turns this into a {@code /api/imagegen/images/{id}} reference. */
    @Transactional
    public UUID store(
        byte[] imageBytes, String contentType, String prompt, String model, String size, String provider
    ) {
        String extension = contentType != null && contentType.contains("png") ? ".png" : ".jpg";
        String objectKey = "generated-images/" + UUID.randomUUID() + extension;
        fileStorageService.put(objectKey, imageBytes, contentType);
        String filename = "image-" + UUID.randomUUID() + extension;
        GeneratedImage image = GeneratedImage.create(filename, contentType, objectKey, prompt, model, size, provider);
        return repository.save(image).getId();
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedImage> find(UUID id) {
        return repository.findById(id);
    }

    /** Backs the Files page's "Generated images" listing, newest first. */
    @Transactional(readOnly = true)
    public List<GeneratedImage> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /** One newest-first window for the paged Files-page listing. */
    @Transactional(readOnly = true)
    public List<GeneratedImage> listPage(long offset, int limit) {
        return repository.findAllByOrderByCreatedAtDescIdDesc(new OffsetLimitRequest(offset, limit));
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return repository.count();
    }
}
