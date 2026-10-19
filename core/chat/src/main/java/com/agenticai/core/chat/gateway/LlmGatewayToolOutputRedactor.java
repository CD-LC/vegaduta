package com.agenticai.core.chat.gateway;

import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies a tenant's LLM-gateway PII-redaction policy to a TOOL RESULT before
 * it is handed back to the model.
 *
 * <p><b>Gap this closes (2026-09-28).</b> {@link LlmGatewayContentPolicyAdvisor}
 * redacts {@code UserMessage}s on the way into a {@code ChatClient} call - but
 * Spring AI runs the tool loop INSIDE the chat model, below every advisor, so a
 * tool result (a CRM lookup returning a customer's phone number, a mailbox read
 * returning an email address) went to the provider on the next internal request
 * with no redaction at all, even for a tenant that had switched PII redaction
 * on. This is the public seam the agent runtime's tool wrapper chain calls so
 * that tool output gets the same treatment as user input.
 *
 * <p>Public wrapper rather than widening {@link LlmGatewayPiiRedactor}: the
 * redactor stays a package-private detail of this package, and the only
 * external entry point is "apply this tenant's policy", which cannot be misused
 * to redact with categories the tenant never enabled.
 *
 * <p><b>Fail-open</b>, the same posture as {@link LlmGatewayContentPolicyService#resolve}:
 * a policy lookup or redaction failure returns the text unchanged rather than
 * failing the tool call - redaction is a privacy control the tenant opted into,
 * not a gate whose failure should cost them their turn.
 */
@Component
public class LlmGatewayToolOutputRedactor {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayToolOutputRedactor.class);

    private final LlmGatewayContentPolicyService policyService;

    public LlmGatewayToolOutputRedactor(LlmGatewayContentPolicyService policyService) {
        this.policyService = policyService;
    }

    /**
     * @param tenantId the tenant whose policy applies; {@code null} (an
     *     internal/system call) returns the text unchanged. Must be called with
     *     that tenant's {@code TenantContext} in force - the policy row lives in
     *     the tenant schema.
     * @return {@code text} with this tenant's enabled PII categories redacted,
     *     or unchanged when redaction is off, nothing matched, or anything failed.
     */
    public String redact(String tenantId, String text) {
        if (tenantId == null || text == null || text.isBlank()) {
            return text;
        }
        try {
            Set<LlmGatewayPiiCategory> categories = policyService.resolve(tenantId).enabledPiiCategories();
            if (categories.isEmpty()) {
                return text;
            }
            String redacted = LlmGatewayPiiRedactor.redact(text, categories);
            return Objects.requireNonNullElse(redacted, text);
        } catch (RuntimeException e) {
            log.warn("Tool-output PII redaction failed for tenant {} - returning the output unredacted (fail-open)",
                tenantId, e);
            return text;
        }
    }
}
