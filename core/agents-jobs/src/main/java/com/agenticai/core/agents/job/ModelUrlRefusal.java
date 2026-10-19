package com.agenticai.core.agents.job;

import com.agenticai.core.chat.LlmHttpClientFactory.ModelServerUrlNotAllowedException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fails an agent job fast when the SSRF guard refused the agent's model server URL: the refusal is a
 * deterministic configuration error (the same URL is refused on every attempt), so it gets a clear code
 * instead of the generic EXECUTION_FAILED. Kept out of {@link AgentJobExecution}, which sits at the
 * file-size limit.
 */
final class ModelUrlRefusal {

    private static final Logger log = LoggerFactory.getLogger(ModelUrlRefusal.class);

    /** Records a job failure with an error code and message. */
    @FunctionalInterface
    interface Fail {
        void fail(UUID jobId, String errorCode, String message);
    }

    private ModelUrlRefusal() {}

    /** @return true when {@code e} (possibly wrapped) was the SSRF refusal and the job was failed. */
    static boolean failIfRefused(RuntimeException e, UUID jobId, Fail fail) {
        ModelServerUrlNotAllowedException blocked = ModelServerUrlNotAllowedException.find(e);
        if (blocked == null) {
            return false;
        }
        log.warn("Agent job {} refused: {}", jobId, blocked.getMessage());
        fail.fail(jobId, "MODEL_URL_NOT_ALLOWED", blocked.getMessage());
        return true;
    }
}
