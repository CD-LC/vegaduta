package com.agenticai.core.agents.tools.code;

import java.util.UUID;

public class CustomCodeToolNotFoundException extends RuntimeException {

    public CustomCodeToolNotFoundException(UUID id) {
        super("No custom code tool found with id " + id);
    }
}
