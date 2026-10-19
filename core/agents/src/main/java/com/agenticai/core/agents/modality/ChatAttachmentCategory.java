package com.agenticai.core.agents.modality;

import java.util.Locale;
import java.util.Set;

/**
 * Stage 4 (Chat file upload) - the attachment kinds a chat turn can carry,
 * derived from the uploaded file's HTTP content type.
 *
 * <p>IMAGE/AUDIO/VIDEO map 1:1 onto {@link
 * com.agenticai.core.providers.ProviderModelCatalogEntry}'s {@code
 * supportsImageInput}/{@code supportsAudioInput}/{@code supportsVideoInput}
 * flags. {@link #DOCUMENT} was added later (agent-page playground parity with
 * the inline {@code /playground} path) and is matched by a FULL content-type
 * allow-list ({@link #DOCUMENT_CONTENT_TYPES}), not a top-level wildcard -
 * "application/*" and "text/*" cover far more than the handful of document
 * formats a multimodal chat model actually accepts. Its per-model support is
 * NOT a {@code ProviderModelResponse} flag; it is resolved from {@code
 * ModelModalitiesService} (the same source the frontend's {@code
 * AgentModalitiesView.document} advertises), so the attach button and the
 * server-side upload check never disagree.
 *
 * <p>This one-turn chat attachment is still distinct from Knowledge Stage
 * F3a's {@code /api/knowledge/upload}, which builds a persistent searchable
 * knowledge base rather than handing raw bytes to the model for a single turn.
 */
public enum ChatAttachmentCategory {
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
    /**
     * Data-analysis sandbox (gap-analysis item 9): an .xlsx workbook. No chat model takes it as
     * native input, so it is never sent to the model - it is mounted into the E2B code-execution
     * sandbox's working directory instead (see {@code SandboxFileRegistry}) and so needs no
     * model-modality check, only an agent with code execution switched on.
     */
    SPREADSHEET;

    /** Content types accepted as {@link #SPREADSHEET} (sandbox-only inputs). */
    static final Set<String> SPREADSHEET_CONTENT_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    /** True for a (possibly parameterised) content type that is sandbox-only and must NOT be sent to the model. */
    public static boolean isSandboxOnly(String contentType) {
        if (contentType == null) {
            return false;
        }
        return SPREADSHEET_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim());
    }

    /** True for a (possibly parameterised) content type in {@link #DOCUMENT_CONTENT_TYPES}. */
    public static boolean isDocument(String contentType) {
        if (contentType == null) {
            return false;
        }
        return DOCUMENT_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim());
    }

    /**
     * The document content types accepted as a one-turn chat attachment - the
     * SAME set the inline web path ({@code ChatAttachments.tsx}) offers, so the
     * two playgrounds stay in lockstep. {@code ModelModalities.document}
     * ("PDF as native chat input") is conservative about which models it holds
     * for; a model that lacks it rejects all of these at upload time.
     */
    static final Set<String> DOCUMENT_CONTENT_TYPES = Set.of(
        "application/pdf", "text/plain", "text/csv", "application/json"
    );

    /**
     * @throws UnsupportedAttachmentTypeException if {@code contentType} is
     *     null/blank, or is neither an image/audio/video file nor one of the
     *     allow-listed document types (PDF/TXT/CSV/JSON) - a .zip or an
     *     arbitrary {@code application/*} type is still rejected here.
     */
    public static ChatAttachmentCategory fromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new UnsupportedAttachmentTypeException("Uploaded file has no detectable content type");
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        // Strip any parameter (e.g. "text/csv; charset=utf-8") before the
        // allow-list check - a browser may append a charset to text types.
        String bare = normalized.split(";", 2)[0].trim();
        if (DOCUMENT_CONTENT_TYPES.contains(bare)) {
            return DOCUMENT;
        }
        if (SPREADSHEET_CONTENT_TYPES.contains(bare)) {
            return SPREADSHEET;
        }
        String topLevel = bare.split("/", 2)[0];
        return switch (topLevel) {
            case "image" -> IMAGE;
            case "audio" -> AUDIO;
            case "video" -> VIDEO;
            default -> throw new UnsupportedAttachmentTypeException(
                "Unsupported attachment type '" + contentType
                    + "' - chat attachments must be an image, audio, video, or supported document (PDF, TXT, CSV, JSON) file"
            );
        };
    }
}
