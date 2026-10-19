package com.agenticai.core.chat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Builds the OpenTelemetry GenAI {@code invoke_agent} span (as a Micrometer
 * observation that the micrometer-tracing OTel bridge exports) around one
 * agent-attributed model call, and derives the prompt-version fingerprint.
 *
 * <p><b>Parent/child.</b> {@link LlmMetricsAdvisor} opens this observation's
 * scope around the rest of the advisor chain, so the Spring AI {@code chat}
 * span and every {@code execute_tool} span ({@link GenAiToolObservationConvention})
 * of that call are its children - the tool loop runs inside the model call.
 *
 * <p><b>Cardinality.</b> Only operation, system and model are low-cardinality
 * (they become meter tags if a MeterObservationHandler is present). Agent id,
 * version, prompt hash, session, tenant and user are high-cardinality: span
 * attributes only, never meter tags.
 *
 * <p><b>Content is never recorded.</b> The prompt version is a short SHA-256
 * of the system message, so a prompt edit is visible on the trace without the
 * prompt text (or anything derived reversibly from it) leaving the process -
 * the same policy as {@code spring.ai.chat.observations.log-prompt=false}.
 */
final class AgentInvocationObservation {

    static final String OPERATION_NAME = "invoke_agent";
    private static final int PROMPT_VERSION_HEX_CHARS = 12;

    private AgentInvocationObservation() {
    }

    /** {@code null} when tracing is a no-op or the call is not attributable to an agent. */
    static Observation start(
        ObservationRegistry registry, String system, String model, UUID agentId, UUID agentVersionId,
        String promptVersion, String sessionId, String tenantId, String userId
    ) {
        return start(registry, system, model, agentId, agentVersionId, promptVersion, sessionId, tenantId, userId,
            GenAiAttributeNames.DEFAULT);
    }

    /** As above; {@code names} picks the legacy / new / both spelling of the provider attribute. */
    static Observation start(
        ObservationRegistry registry, String system, String model, UUID agentId, UUID agentVersionId,
        String promptVersion, String sessionId, String tenantId, String userId, GenAiAttributeNames names
    ) {
        if (registry == null || registry.isNoop() || agentId == null) {
            return null;
        }
        try {
            Observation observation = Observation.createNotStarted(OPERATION_NAME, registry)
                .contextualName(OPERATION_NAME + " " + agentId)
                .lowCardinalityKeyValue("gen_ai.operation.name", OPERATION_NAME)
                .lowCardinalityKeyValue("gen_ai.request.model", model)
                .highCardinalityKeyValue("gen_ai.agent.id", agentId.toString());
            // gen_ai.system (deprecated) and/or gen_ai.provider.name, per app.observability.genai-attrs.
            for (String providerKey : (names == null ? GenAiAttributeNames.DEFAULT : names).providerKeys()) {
                observation.lowCardinalityKeyValue(providerKey, system);
            }
            optional(observation, "gen_ai.agent.version", agentVersionId == null ? null : agentVersionId.toString());
            optional(observation, "vegaduta.prompt.version", promptVersion);
            optional(observation, "gen_ai.conversation.id", sessionId);
            // Tenant stays its own attribute; the Langfuse user is the real
            // user (subject) when known - see LlmMetricsAdvisor.langfuseUserId.
            optional(observation, "vegaduta.tenant.id", tenantId);
            optional(observation, "langfuse.user.id", userId == null || userId.isBlank() ? tenantId : userId);
            optional(observation, "langfuse.session.id", sessionId);
            return observation.start();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void optional(Observation observation, String key, String value) {
        if (value != null && !value.isBlank()) {
            observation.highCardinalityKeyValue(KeyValue.of(key, value));
        }
    }

    /** Short SHA-256 fingerprint of the request's system message, or {@code null} when it has none. */
    static String promptVersionOf(Prompt prompt) {
        try {
            if (prompt == null) {
                return null;
            }
            SystemMessage system = prompt.getSystemMessage();
            String text = system == null ? null : system.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, PROMPT_VERSION_HEX_CHARS);
        } catch (NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }
}
