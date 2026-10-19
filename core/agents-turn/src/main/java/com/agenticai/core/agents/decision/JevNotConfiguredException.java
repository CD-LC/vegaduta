package com.agenticai.core.agents.decision;

import com.agenticai.core.providers.capability.CapabilityNotConfiguredException;

/**
 * Thrown when a Jev-dependent call on the {@code /api/jev} compat surface
 * (e.g. the playground) runs with no key resolved for the tenant - mapped to
 * a 400 by {@code GlobalExceptionHandler}. A subtype of the generic {@link
 * CapabilityNotConfiguredException}: the legacy endpoint keeps its own type,
 * the generic {@code /api/capabilities/decision} path maps the same failure
 * the same way.
 */
public class JevNotConfiguredException extends CapabilityNotConfiguredException {

    public JevNotConfiguredException(String message) {
        super(message);
    }
}
