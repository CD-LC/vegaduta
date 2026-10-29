package com.agenticai.core.agents.appbuilder;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantStatusGuard;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * App Builder generation routing fix (2026-08-06) - {@code POST
 * /api/internal/app-builder/generate-files}.
 *
 * <p><b>Service-to-service auth, not a user JWT</b> - identical posture and implementation shape to
 * {@code CompGrantController} (core/tenancy), the established precedent for exactly this class of
 * caller in this codebase. app-builder is a separate microservice acting on behalf of a tenant with
 * no end-user Keycloak token to forward, so the credential is a shared {@code X-Service-Token}
 * header validated here against the configured secret, plus an {@code X-Tenant-Id} header
 * (app-builder's own {@code TenantContext} already carries this) telling this endpoint which
 * tenant's plan/BYOK credentials to resolve against. The route is {@code permitAll()} at the Spring
 * Security layer (see {@code SecurityConfig}'s {@code /api/internal/app-builder/**} rule) - the JWT
 * filter never runs for it, and THIS controller is the sole gate. The gate itself lives in {@link
 * AppBuilderServiceAuth}, shared with {@link AppBuilderImageController}.
 *
 * <p><b>The token is not bound to a tenant.</b> Whoever holds it could once name ANY tenant schema
 * in {@code X-Tenant-Id} and drive generation billed to and credentialled by that tenant. {@link
 * AppBuilderServiceAuth} now bounds the claim: reserved schemas are refused, {@code
 * app.app-builder.allowed-tenants} ({@code APP_BUILDER_ALLOWED_TENANTS}) scopes which tenants the
 * token may act for, and {@code TenantStatusGuard} rejects tenants that do not exist or were
 * stopped/suspended.
 *
 * <p><b>Fail closed.</b> An unconfigured/blank token on this server rejects every call (401) rather
 * than accepting any header value. A missing header is 401; a present-but-wrong header is 403.
 * Comparison is constant-time.
 *
 * <p><b>Auth runs before body parsing</b> (same DAST-2026-07-27 D4 fix {@code CompGrantController}
 * applies) - the body is bound as a raw, always-bindable {@code String}, {@link
 * AppBuilderServiceAuth#authenticate} runs first, and only then is it parsed/validated - an
 * unauthenticated caller never learns the request schema from a 400.
 *
 * <p><b>TenantContext scoping.</b> {@link AppBuilderCompletionService} and everything it calls
 * (provider-credential lookup, plan resolution) read tenant identity from {@link
 * TenantContext#current()} - a per-request ThreadLocal normally populated by {@code
 * TenantContextFilter} from a validated JWT. Since this endpoint has no JWT, it establishes that
 * same context manually from the (now-authenticated) {@code X-Tenant-Id} header, scoped to this one
 * request via try/finally - never leaking into any other request handled by this thread afterward,
 * same discipline {@code TenantContextFilter} itself already follows.
 */
@RestController
@RequestMapping("/api/internal/app-builder")
@Profile("security")
public class AppBuilderCompletionController {

    private static final int MAX_RETRIES = 1;

    private final AppBuilderCompletionService completionService;
    private final AppBuilderServiceAuth serviceAuth;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AppBuilderCompletionController(
            AppBuilderCompletionService completionService,
            @Value("${app.app-builder.service-token:}") String serviceToken,
            @Value("${app.app-builder.allowed-tenants:}") String allowedTenants,
            ObjectMapper objectMapper,
            Validator validator,
            ObjectProvider<TenantStatusGuard> tenantStatusGuard) {
        this.completionService = completionService;
        this.serviceAuth = new AppBuilderServiceAuth(serviceToken, allowedTenants, tenantStatusGuard);
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/generate-files")
    public AppBuilderGenerateFilesResult generateFiles(
            @RequestHeader(value = "X-Service-Token", required = false) String presentedToken,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody(required = false) String rawBody) {
        // Auth FIRST, on a raw, un-parsed body - see class javadoc.
        serviceAuth.authenticate(presentedToken);
        if (tenantId == null || tenantId.isBlank()) {
            throw new AppBuilderTokenMissingException("Missing X-Tenant-Id header.");
        }
        serviceAuth.authorizeTenantClaim(tenantId);
        AppBuilderGenerateFilesRequest request = parseAndValidate(rawBody);

        TenantContext.set(
                TenantContext.of(tenantId, "app-builder-service", Set.of(), "app-builder"));
        try {
            serviceAuth.requireUsableTenant(tenantId);
            AppBuilderGeneration generation = completionService.generateFiles(tenantId, request);
            // ONE corrective retry (matches generate.py's own pre-existing
            // v0 scope) - only when the first attempt returned zero usable
            // files AND the caller didn't already send previousFailure
            // (i.e. this isn't already the retry call itself; app-builder
            // drives the retry by calling this endpoint a second time with
            // previousFailure set to its own build/test failure output, not
            // by this controller looping on empty results).
            if (generation.files().isEmpty()
                    && (request.previousFailure() == null || request.previousFailure().isBlank())) {
                for (int attempt = 0;
                        attempt < MAX_RETRIES && generation.files().isEmpty();
                        attempt++) {
                    // A format nudge, NOT previousFailure: that field means
                    // "the build/test command failed", which it did not.
                    generation = completionService.generateFiles(tenantId, request.asFormatRetry());
                }
            }
            return new AppBuilderGenerateFilesResult(
                    generation.files(),
                    generation.providerName(),
                    generation.model(),
                    generation.summary());
        } finally {
            TenantContext.clear();
        }
    }

    private AppBuilderGenerateFilesRequest parseAndValidate(String rawBody) {
        AppBuilderGenerateFilesRequest request;
        try {
            request =
                    objectMapper.readValue(
                            rawBody == null ? "" : rawBody, AppBuilderGenerateFilesRequest.class);
        } catch (Exception e) {
            throw new AppBuilderValidationException(
                    "The request body is missing or not valid JSON for this endpoint.");
        }
        Set<ConstraintViolation<AppBuilderGenerateFilesRequest>> violations =
                validator.validate(request);
        if (!violations.isEmpty()) {
            String message =
                    violations.stream()
                            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                            .sorted()
                            .reduce((a, b) -> a + "; " + b)
                            .orElse("Invalid request");
            throw new AppBuilderValidationException(message);
        }
        return request;
    }

}
