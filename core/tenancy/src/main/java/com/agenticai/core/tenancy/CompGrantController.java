package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.dto.CompGrantRequest;
import com.agenticai.core.tenancy.dto.CompGrantResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Academy Fast-Track Scholarship one-click fulfilment - {@code
 * POST /api/tenant/comp-grant} (CORE-COMP-GRANT-CONTRACT.md, "Core side").
 *
 * <p><b>Service-to-service auth, not a user JWT.</b> The Academy calls this from
 * BOTH its admin paths (its own admin page AND the web-admin proxy), and the
 * web-admin-triggered path has no end-user token to forward - so the credential
 * is a shared service token in the {@code X-Service-Token} header, validated here
 * against {@code CORE_COMP_SERVICE_TOKEN} (config {@code app.tenant.comp.service-token}).
 * The route is therefore {@code permitAll()} at the Spring Security layer (see
 * {@code SecurityConfig}'s {@code /api/tenant/comp-grant} rule) exactly like the
 * {@code /webhook/**} and device-token endpoints - the JWT filter never runs for
 * it, and THIS controller is the sole gate. Structurally identical posture to
 * {@code McpApiKeyAuthFilter} / the Twilio webhook-signature checks.
 *
 * <p><b>Fail closed.</b> If {@code CORE_COMP_SERVICE_TOKEN} is unset/blank on this
 * server, every call is rejected (401) rather than any token being accepted - a
 * deployment that never configured the secret cannot have its plans flipped by an
 * empty header. A missing header is 401; a present-but-wrong header is 403 (see
 * the two exception types). The comparison is constant-time to avoid leaking the
 * token a byte at a time through response timing.
 *
 * <p><b>Auth runs before ANY body parsing/validation (DAST-2026-07-27 D4 fix).</b>
 * The body used to be bound via a typed {@code @Valid @RequestBody CompGrantRequest}
 * parameter - but Spring MVC resolves EVERY method argument (including a {@code
 * @Valid @RequestBody}'s JSON deserialization and bean validation) before the
 * controller method body ever runs, regardless of parameter order. That meant an
 * unauthenticated caller with a missing/malformed body got a 400 naming the exact
 * required fields (or, for a body that failed to deserialize at all - e.g. an
 * enum-typed field given a value that doesn't match {@code TenantPlan} - a generic
 * "not valid JSON" 400) BEFORE {@code authenticate()} (previously the method body's
 * first line) ever got a chance to reject the call. Both cases are the same root
 * cause: argument resolution, not the method body, was the actual "first" thing to
 * run. Fixed by reading the body as a raw, always-bindable {@code String} (no JSON
 * parsing or validation happens during argument resolution for a plain {@code
 * String} parameter), calling {@code authenticate()} FIRST, and only then manually
 * parsing/validating the body in {@link #parseAndValidate}. An unauthenticated
 * caller now always gets 401/403 - the schema is never disclosed, and no
 * "well-formed body, wrong token" case can end up looking like a 400 anymore.
 *
 * <p>Deliberately NOT wired to also accept a {@code ROLE_platform-admin} Keycloak
 * token: the contract lists that as optional ("but the service token is what the
 * Academy sends"), and the Academy never sends a JWT here. A platform admin who
 * wants to comp-grant by hand already has the web-admin plan override
 * ({@code PlatformTenantPlanService}); adding a JWT branch to this
 * permitAll route would be a second, unused auth path to keep correct. Left as a
 * documented follow-up if a direct platform-admin caller ever materializes.
 */
@RestController
@RequestMapping("/api/tenant/comp-grant")
@Profile("security")
public class CompGrantController {

    private final CompGrantService compGrantService;
    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final java.util.Set<String> allowedTenants;

    public CompGrantController(
        CompGrantService compGrantService,
        @Value("${app.tenant.comp.service-token:}") String serviceToken,
        @Value("${app.tenant.comp.allowed-tenants:}") String allowedTenantsRaw,
        ObjectMapper objectMapper,
        Validator validator
    ) {
        this.compGrantService = compGrantService;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
        this.validator = validator;
        java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
        if (allowedTenantsRaw != null && !allowedTenantsRaw.isBlank()) {
            for (String part : allowedTenantsRaw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
        }
        this.allowedTenants = java.util.Collections.unmodifiableSet(parsed);
    }

    @PostMapping
    public CompGrantResponse compGrant(
        @RequestHeader(value = "X-Service-Token", required = false) String presentedToken,
        @RequestBody(required = false) String rawBody
    ) {
        // Auth FIRST, on a raw, un-parsed body - see class javadoc.
        authenticate(presentedToken);
        CompGrantRequest request = parseAndValidate(rawBody);
        // The service token is one static credential not bound to a tenant:
        // when the allow-list is configured it must not flip the plan of a
        // tenant outside it (same bound as app.app-builder.allowed-tenants).
        if (!allowedTenants.isEmpty() && !allowedTenants.contains(request.tenantId().toString())) {
            throw new CompGrantTokenInvalidException(
                "tenantId is not in this server's comp-grant tenant allow-list.");
        }
        return compGrantService.grant(request);
    }

    private CompGrantRequest parseAndValidate(String rawBody) {
        CompGrantRequest request;
        try {
            request = objectMapper.readValue(rawBody == null ? "" : rawBody, CompGrantRequest.class);
        } catch (Exception e) {
            throw new CompGrantMalformedBodyException(
                "The request body is missing or not valid JSON for this endpoint.");
        }
        Set<ConstraintViolation<CompGrantRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
            throw new CompGrantValidationException(message);
        }
        return request;
    }

    private void authenticate(String presentedToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            // Fail closed: no configured secret means no caller can ever be trusted.
            throw new CompGrantTokenMissingException(
                "Comp-grant service token is not configured on this server; endpoint disabled.");
        }
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new CompGrantTokenMissingException("Missing X-Service-Token header.");
        }
        boolean matches = MessageDigest.isEqual(
            serviceToken.getBytes(StandardCharsets.UTF_8),
            presentedToken.getBytes(StandardCharsets.UTF_8)
        );
        if (!matches) {
            throw new CompGrantTokenInvalidException("Invalid X-Service-Token.");
        }
    }
}
