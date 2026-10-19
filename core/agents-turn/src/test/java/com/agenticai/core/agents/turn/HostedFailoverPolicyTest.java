package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.guardrails.GuardrailViolationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the "is another hosted provider worth trying?" classification - the
 * distinction the hosted chain used to lack entirely (it advanced on ANY
 * upstream error, so a content-policy refusal was replayed against the whole
 * pool before the user saw the same message).
 */
class HostedFailoverPolicyTest {

    private static UpstreamProviderException upstream(int status, String detail) {
        return new UpstreamProviderException(status, "The LLM provider rejected the request (HTTP " + status + "): " + detail, "…");
    }

    @Test
    void providerSideFailuresAdvanceTheChain() {
        // The live symptom: Gemini row, model id Google doesn't serve.
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(404, "models/gemini-x is not found for API version v1beta")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(401, "invalid api key")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(403, "permission denied")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(402, "insufficient credits")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(429, "rate_limit_exceeded")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(408, "timed out")));
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(503, "upstream unavailable")));
        // ProviderErrorTranslator's fallback status for connection errors and
        // unstructured exceptions - a genuine provider-side failure.
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(502, "connection reset")));
        // A plain 400 from an OpenAI-compat host is overwhelmingly "this
        // provider doesn't accept that model/param" - the next row may.
        assertTrue(HostedFailoverPolicy.isRetryable(upstream(400, "unsupported parameter: top_p")));
    }

    @Test
    void userInputFailuresFailFast() {
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(400, "content_policy_violation")));
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(400, "Response was blocked by safety filters")));
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(400, "This model's maximum context length is 8192 tokens")));
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(413, "payload too large")));
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(422, "unprocessable entity")));
        // Status alone must not rescue an input-shaped detail: a provider that
        // reports a context overflow as a 500 still can't be fixed by switching.
        assertFalse(HostedFailoverPolicy.isRetryable(upstream(500, "context length exceeded")));
    }

    @Test
    void nonProviderFailuresAreNeverRetried() {
        assertFalse(HostedFailoverPolicy.isRetryable(new GuardrailViolationException("some_tool", "blocked")));
        assertFalse(HostedFailoverPolicy.isRetryable(new IllegalStateException("boom")));
        assertFalse(HostedFailoverPolicy.isRetryable(null));
    }
}
