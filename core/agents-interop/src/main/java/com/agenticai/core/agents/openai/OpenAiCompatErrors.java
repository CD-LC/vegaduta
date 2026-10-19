package com.agenticai.core.agents.openai;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.AgentModelNotSupportedException;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.chat.gateway.LlmGatewayContentPolicyViolationException;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.providers.NoActiveProviderException;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderNotActiveException;
import com.agenticai.core.providers.validation.ProviderNotSupportedException;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Maps platform exceptions onto OpenAI's {@code {"error":{message,type,param,code}}} envelope. */
final class OpenAiCompatErrors {

    /** Status, OpenAI error type, OpenAI error code, client-safe message, optional Retry-After. */
    record Info(int status, String type, String code, String message, long retryAfterSeconds) {}

    private OpenAiCompatErrors() {}

    static Info classify(Throwable e) {
        if (e instanceof OpenAiCompatException o) {
            return new Info(o.status(), o.type(), o.code(), o.getMessage(), o.retryAfterSeconds());
        }
        if (e instanceof PerUserBudgetExhaustedException b) {
            long retry = b.getResetSeconds() == null ? 0L : b.getResetSeconds();
            return new Info(429, "insufficient_quota", "insufficient_quota", b.getMessage(), retry);
        }
        if (e instanceof TenantLimitExceededException t) {
            return new Info(403, "permission_error", "plan_limit_exceeded", t.getMessage(), 0L);
        }
        if (e instanceof TenantUnavailableException t) {
            return new Info(403, "permission_error", "tenant_unavailable", t.getMessage(), 0L);
        }
        if (e instanceof AgentNotFoundException || e instanceof ResourceAccessDeniedException) {
            // Same anti-enumeration answer for "does not exist" and "not yours".
            return new Info(404, "invalid_request_error", "model_not_found",
                "The model does not exist or you do not have access to it.", 0L);
        }
        if (e instanceof HttpMessageNotReadableException) {
            return new Info(400, "invalid_request_error", "invalid_json", "The request body is not valid JSON.", 0L);
        }
        if (e instanceof LlmGatewayContentPolicyViolationException) {
            return new Info(400, "invalid_request_error", "content_filter",
                "The request was rejected by the content policy.", 0L);
        }
        if (e instanceof LlmGatewayLimitExceededException) {
            return new Info(429, "rate_limit_exceeded", "rate_limit_exceeded",
                "A usage limit for this workspace has been reached. Please retry later.", 0L);
        }
        if (e instanceof AgentModelNotSupportedException) {
            return new Info(400, "invalid_request_error", "model_not_supported",
                "The agent's configured model is not supported by its provider.", 0L);
        }
        if (e instanceof ProviderNotActiveException || e instanceof NoActiveProviderException
            || e instanceof ProviderNotSupportedException || e instanceof ProviderModelCatalogEmptyException) {
            return new Info(503, "server_error", "provider_unavailable",
                "The AI provider for this agent is not available right now.", 0L);
        }
        if (e instanceof UpstreamProviderException u) {
            // Fixed client-safe text: never the raw provider body, a key hint or a stack trace.
            int up = u.getUpstreamStatus();
            if (up == 429) {
                return new Info(429, "rate_limit_exceeded", "rate_limit_exceeded",
                    "The upstream model provider is rate limiting requests. Please retry shortly.", 0L);
            }
            if (up == 503 || up == 529) {
                return new Info(503, "server_error", "provider_unavailable",
                    "The upstream model provider is temporarily unavailable.", 0L);
            }
            // 400/401/403/404/5xx from the provider are OUR gateway's problem, not the caller's
            // credentials: surface as a bad gateway, never as the caller's own 401/403.
            return new Info(502, "server_error", "upstream_error",
                "The upstream model provider returned an error.", 0L);
        }
        if (e instanceof IllegalArgumentException) {
            // Never echo the message: it can carry internal detail. The controller raises its own
            // deliberate 400s as OpenAiCompatException with a client-safe message.
            return new Info(400, "invalid_request_error", "invalid_request", "The request is invalid.", 0L);
        }
        return new Info(500, "server_error", null, "The server had an error while processing your request.", 0L);
    }

    static String toJson(ObjectMapper mapper, Info info) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode err = root.putObject("error");
        err.put("message", info.message() == null ? "" : info.message());
        err.put("type", info.type());
        err.putNull("param");
        if (info.code() == null) {
            err.putNull("code");
        } else {
            err.put("code", info.code());
        }
        return root.toString();
    }
}
