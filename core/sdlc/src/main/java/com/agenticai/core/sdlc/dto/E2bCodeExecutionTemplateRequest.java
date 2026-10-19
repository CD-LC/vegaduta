package com.agenticai.core.sdlc.dto;

/**
 * @param templateId the E2B template ID the tenant built via {@code e2b template create} from
 *     core/sdlc/e2b-template/ (see that directory's README.md) - blank/null clears it.
 */
public record E2bCodeExecutionTemplateRequest(String templateId) {
}
