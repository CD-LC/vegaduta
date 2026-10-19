package com.agenticai.core.quality.simulation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 404 for a missing/mismatched simulation session - same {@code @ResponseStatus} choice as {@code EvalDatasetNotFoundException}. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SimulationSessionNotFoundException extends RuntimeException {

    public SimulationSessionNotFoundException(UUID sessionId) {
        super("No simulation session found with id " + sessionId);
    }
}
