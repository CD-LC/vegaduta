package com.agenticai.core.audit;

import java.util.UUID;

public class IssueNotFoundException extends RuntimeException {

    public IssueNotFoundException(UUID id) {
        super("Platform issue not found: " + id);
    }
}
