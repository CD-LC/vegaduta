package com.agenticai.core.brief;

import com.agenticai.core.agents.assist.AssistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The planner's one LLM primitive, shared by {@link BriefPlannerService}, {@link PlannerConnectors}
 * and {@link PlannerReview}: a {@link AssistService#complete} call whose reply must be a JSON
 * object.
 */
final class PlannerLlm {

    private static final Logger log = LoggerFactory.getLogger(PlannerLlm.class);

    private final AssistService assistService;
    private final ObjectMapper objectMapper;

    PlannerLlm(AssistService assistService, ObjectMapper objectMapper) {
        this.assistService = assistService;
        this.objectMapper = objectMapper;
    }

    /**
     * One LLM call whose reply must be a JSON object; an unparseable reply is a 422 naming {@code
     * section}.
     */
    JsonNode callJson(String section, String system, String user) {
        String raw = assistService.complete(system, user);
        JsonNode node = LlmJson.parseObject(objectMapper, raw);
        if (node == null) {
            log.warn("Brief planner: unparseable model output for section {}", section);
            throw new BriefValidationException(
                    section,
                    "The model's answer for this part could not be understood - try again or rephrase the request.");
        }
        return node;
    }
}
