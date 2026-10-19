package com.agenticai.core.agents.tools.openapi;

import java.util.UUID;

public class OpenApiToolNotFoundException extends RuntimeException {

    public OpenApiToolNotFoundException(UUID id) {
        super("No imported OpenAPI tool found with id " + id);
    }
}
