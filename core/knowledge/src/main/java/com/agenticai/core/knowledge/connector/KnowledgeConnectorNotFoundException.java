package com.agenticai.core.knowledge.connector;

import java.util.UUID;

/** 404 for connector ids - same anti-enumeration posture as the rest of this module. */
public class KnowledgeConnectorNotFoundException extends RuntimeException {

    public KnowledgeConnectorNotFoundException(UUID id) {
        super("Knowledge connector not found: " + id);
    }
}
