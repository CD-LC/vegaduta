package com.agenticai.core.brief;

import java.util.UUID;

/** No brief with this id in the caller's tenant schema. Mapped to HTTP 404 by {@link BriefController}. */
public class BriefNotFoundException extends RuntimeException {

    public BriefNotFoundException(UUID id) {
        super("Brief " + id + " was not found.");
    }
}
