package com.agenticai.core.quality.dataset;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 404 for a missing/mismatched run - see {@link EvalDatasetNotFoundException} for the mapping choice. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class EvalRunNotFoundException extends RuntimeException {

    public EvalRunNotFoundException(UUID runId) {
        super("Eval run " + runId + " was not found for this dataset.");
    }
}
