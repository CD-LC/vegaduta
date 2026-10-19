package com.agenticai.core.chat.gateway;

import com.agenticai.core.security.TenantContext;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant settings API for the LLM-gateway content policy (PII redaction and
 * prompt-injection screening, both OFF until a tenant opts in).
 *
 * <p>GET is readable by any authenticated tenant member (falls through to
 * {@code anyRequest().authenticated()}); PUT requires {@code
 * PERM_MANAGE_GUARDRAILS} - see {@code SecurityConfig}. The tenant is always
 * taken from {@link TenantContext}, never from the request body.
 */
@RestController
@RequestMapping("/api/tenant/llm-gateway/content-policy")
public class LlmGatewayContentPolicyController {

    private final LlmGatewayContentPolicyService service;

    public LlmGatewayContentPolicyController(LlmGatewayContentPolicyService service) {
        this.service = service;
    }

    /** Every field is required on PUT so a partial body can never silently flip a safety setting. */
    public record ContentPolicyRequest(
        Boolean piiRedactionEnabled,
        Boolean piiRedactEmail,
        Boolean piiRedactPhone,
        Boolean piiRedactGovernmentId,
        Boolean piiRedactCreditCard,
        Boolean injectionScreeningEnabled,
        String injectionScreeningAction
    ) {}

    public record ContentPolicyResponse(
        boolean piiRedactionEnabled,
        boolean piiRedactEmail,
        boolean piiRedactPhone,
        boolean piiRedactGovernmentId,
        boolean piiRedactCreditCard,
        boolean injectionScreeningEnabled,
        String injectionScreeningAction
    ) {
        static ContentPolicyResponse from(LlmGatewayContentPolicy policy) {
            return new ContentPolicyResponse(
                policy.isPiiRedactionEnabled(),
                policy.isPiiRedactEmail(),
                policy.isPiiRedactPhone(),
                policy.isPiiRedactGovernmentId(),
                policy.isPiiRedactCreditCard(),
                policy.isInjectionScreeningEnabled(),
                policy.getInjectionScreeningAction().name()
            );
        }
    }

    @GetMapping
    public ContentPolicyResponse get() {
        return ContentPolicyResponse.from(service.resolve(tenantId()));
    }

    @PutMapping
    public ContentPolicyResponse update(@RequestBody ContentPolicyRequest request) {
        String tenantId = tenantId();
        LlmGatewayContentPolicyService.PolicyUpdate update = toUpdate(request);
        String actor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return ContentPolicyResponse.from(service.upsert(tenantId, update, actor));
    }

    static LlmGatewayContentPolicyService.PolicyUpdate toUpdate(ContentPolicyRequest request) {
        if (request == null) {
            throw badRequest("Request body is required.");
        }
        return new LlmGatewayContentPolicyService.PolicyUpdate(
            required(request.piiRedactionEnabled(), "piiRedactionEnabled"),
            required(request.piiRedactEmail(), "piiRedactEmail"),
            required(request.piiRedactPhone(), "piiRedactPhone"),
            required(request.piiRedactGovernmentId(), "piiRedactGovernmentId"),
            required(request.piiRedactCreditCard(), "piiRedactCreditCard"),
            required(request.injectionScreeningEnabled(), "injectionScreeningEnabled"),
            parseAction(request.injectionScreeningAction())
        );
    }

    private static boolean required(Boolean value, String field) {
        if (value == null) {
            throw badRequest(field + " is required.");
        }
        return value;
    }

    private static LlmGatewayContentPolicyAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw badRequest("injectionScreeningAction is required (FLAG or BLOCK).");
        }
        try {
            return LlmGatewayContentPolicyAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("injectionScreeningAction must be FLAG or BLOCK.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/llm-gateway/content-policy request - SecurityConfig should have blocked this"));
    }
}
