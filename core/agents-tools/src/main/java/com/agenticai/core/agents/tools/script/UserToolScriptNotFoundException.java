package com.agenticai.core.agents.tools.script;

import java.util.UUID;

public class UserToolScriptNotFoundException extends RuntimeException {

    public UserToolScriptNotFoundException(UUID id) {
        super("No user-created tool found with id " + id);
    }
}
