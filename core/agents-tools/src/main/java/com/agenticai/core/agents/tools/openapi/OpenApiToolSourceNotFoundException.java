package com.agenticai.core.agents.tools.openapi;

import java.util.UUID;

public class OpenApiToolSourceNotFoundException extends RuntimeException {

    public OpenApiToolSourceNotFoundException(UUID id) {
        super("No OpenAPI tool source found with id " + id);
    }
}
