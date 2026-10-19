package com.agenticai.core.documentgen.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The {@code POST /api/documentgen/documents} request body, and the input
 * every {@code DocumentGenerator#generate} implementation receives.
 *
 * @param type which format to render - selects the {@code DocumentGenerator} via {@code DocumentGeneratorRegistry}
 * @param filename the desired filename WITHOUT an extension - {@code
 *     DocumentGenerationService} appends {@link DocumentType#extension()}
 *     when building the stored/served filename (see {@link
 *     DocumentGenerationJobResponse#filename()}, which IS extension-inclusive)
 * @param content the body to render
 */
public record DocumentGenerationRequest(
    @NotNull DocumentType type,
    @NotBlank @Size(max = 255) String filename,
    @NotNull @Valid DocumentContent content
) {
}
