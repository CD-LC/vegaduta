package com.agenticai.core.imagegen.dto;

import com.agenticai.core.imagegen.GeneratedImage;
import java.time.Instant;
import java.util.UUID;

/** Row shape for the Files page's "Generated images" listing - never carries the raw bytes, only what {@code GET /api/imagegen/images/{id}} needs to fetch them. */
public record GeneratedImageResponse(
    UUID id,
    String filename,
    String contentType,
    String prompt,
    String model,
    String size,
    String provider,
    Instant createdAt,
    String downloadUrl
) {
    public static GeneratedImageResponse from(GeneratedImage image) {
        return new GeneratedImageResponse(
            image.getId(),
            image.getFilename(),
            image.getContentType(),
            image.getPrompt(),
            image.getModel(),
            image.getSize(),
            image.getProvider(),
            image.getCreatedAt(),
            "/api/imagegen/images/" + image.getId()
        );
    }
}
