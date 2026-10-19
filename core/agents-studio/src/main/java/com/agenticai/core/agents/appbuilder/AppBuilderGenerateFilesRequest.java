package com.agenticai.core.agents.appbuilder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body of {@code POST /api/internal/app-builder/generate-files}. {@code
 * retrievedFiles} are the few relevant files app-builder's own code-intel
 * retrieval step already picked (this endpoint does no retrieval itself -
 * that stays entirely on the app-builder side, unchanged from before this
 * routing fix). {@code previousFailure} is non-null only on the one
 * corrective-retry call (matches generate.py's existing "apply, run,
 * on-failure feed the structured failure back for ONE retry" shape) - the
 * tenant/auth identity for that retry is unchanged, only the prompt gains
 * this extra context.
 */
public record AppBuilderGenerateFilesRequest(
    @NotBlank(message = "instruction is required") String instruction,
    @NotNull(message = "retrievedFiles is required (may be empty)") List<AppBuilderFileEdit> retrievedFiles,
    String stack,
    String previousFailure,
    // 2026-09-26 "next-gen designer" pass: `context` is freeform structured
    // design notes composed client-side (wireframe spec, extracted palette,
    // SVG markup); `images` are base64 design attachments (sketch photos,
    // Figma exports) for vision-capable models. Both optional.
    String context,
    List<AppBuilderImage> images,
    // 2026-09-29: paths in retrievedFiles whose content is the WHOLE file
    // (anything else is excerpts and must not be rewritten), and the
    // project's file list so the model knows what exists. Both optional;
    // an older app-builder sends neither and every file reads as PARTIAL.
    List<String> fullFilePaths,
    List<String> projectFiles,
    // Set only by the controller's empty-reply retry.
    boolean formatRetry
) {
    /** Back-compat constructor for pre-images callers/tests. */
    public AppBuilderGenerateFilesRequest(
            String instruction, List<AppBuilderFileEdit> retrievedFiles,
            String stack, String previousFailure) {
        this(instruction, retrievedFiles, stack, previousFailure, null, null, null, null, false);
    }

    /** Back-compat constructor for pre-full-file callers/tests. */
    public AppBuilderGenerateFilesRequest(
            String instruction, List<AppBuilderFileEdit> retrievedFiles,
            String stack, String previousFailure, String context, List<AppBuilderImage> images) {
        this(instruction, retrievedFiles, stack, previousFailure, context, images, null, null, false);
    }

    public AppBuilderGenerateFilesRequest asFormatRetry() {
        return new AppBuilderGenerateFilesRequest(instruction, retrievedFiles, stack, previousFailure,
            context, images, fullFilePaths, projectFiles, true);
    }
}
