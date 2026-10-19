package com.agenticai.core.agents.modality;

/**
 * Thrown for a chat attachment whose content type isn't image/audio/video
 * ({@link ChatAttachmentCategory#fromContentType}), or whose category the
 * target agent's resolved model doesn't accept (see {@code
 * ChatAttachmentService}'s upload-time check against {@code
 * ProviderModelCatalogService.findModel}'s {@code supports*Input} flags).
 */
public class UnsupportedAttachmentTypeException extends RuntimeException {

    public UnsupportedAttachmentTypeException(String message) {
        super(message);
    }
}
