package com.agenticai.core.quality.simulation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 404 for a missing simulation run. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SimulationRunNotFoundException extends RuntimeException {

    public SimulationRunNotFoundException(UUID runId) {
        super("No simulation run found with id " + runId);
    }
}
