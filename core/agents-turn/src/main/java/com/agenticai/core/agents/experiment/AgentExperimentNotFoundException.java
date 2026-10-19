package com.agenticai.core.agents.experiment;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 404 for a missing/mismatched experiment. {@code @ResponseStatus} rather
 * than a {@code GlobalExceptionHandler} entry - that handler lives in
 * {@code core/app}, and the annotation gives the same status code without a
 * cross-module edit, same choice {@code EvalDatasetNotFoundException} documents.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AgentExperimentNotFoundException extends RuntimeException {

    public AgentExperimentNotFoundException(UUID experimentId) {
        super("No experiment found with id " + experimentId);
    }
}
