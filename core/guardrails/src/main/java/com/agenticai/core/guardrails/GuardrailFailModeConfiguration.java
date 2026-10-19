package com.agenticai.core.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Applies {@code app.guardrails.fail-mode} to {@link GuardrailFailMode} at startup. */
@Component
class GuardrailFailModeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardrailFailModeConfiguration.class);

    GuardrailFailModeConfiguration(@Value("${app.guardrails.fail-mode:closed}") String configured) {
        GuardrailFailMode mode = GuardrailFailMode.parse(configured);
        GuardrailFailMode.set(mode);
        if (mode == GuardrailFailMode.OPEN) {
            log.warn(
                    "app.guardrails.fail-mode=open: a failing guardrail check lets the call through."
                            + " Use only where availability outranks the bypass risk.");
        } else {
            log.info("app.guardrails.fail-mode=closed: a failing guardrail check refuses the call");
        }
    }
}
