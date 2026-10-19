package com.agenticai.core.quality.dataset;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 404 for a missing/mismatched dataset. {@code @ResponseStatus} rather than
 * a {@code GlobalExceptionHandler} entry, deliberately: that handler lives
 * in {@code core/app}, outside this workstream's file ownership, and the
 * annotation gives the same status code without a cross-module edit. Wiring
 * these three exceptions into the central handler (for the richer
 * {@code {error: ...}} body shape) is a noted follow-up for whoever owns
 * that file.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class EvalDatasetNotFoundException extends RuntimeException {

    public EvalDatasetNotFoundException(UUID datasetId) {
        super("Eval dataset " + datasetId + " was not found for this agent.");
    }
}
