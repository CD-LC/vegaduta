package com.agenticai.core.tenancy;

import com.agenticai.core.security.JwtClaimPaths;
import com.agenticai.core.tenancy.demo.DemoConversionService;
import com.agenticai.core.tenancy.dto.OnboardingRequest;
import com.agenticai.core.tenancy.dto.SignupResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/onboarding} - provisions a tenant for an ALREADY-authenticated
 * user who has no tenant yet: the social-login (e.g. Google) path, where
 * Keycloak's identity-provider first-login created the user but no {@code
 * /api/signup} ever ran, so they arrive with a valid token but no organization.
 *
 * <p>Authenticated (the caller has a token) but deliberately exempted from
 * {@link com.agenticai.core.security.TenantContextFilter}'s missing-tenant 403
 * (see that filter's {@code onboardingPath} check) - it's the one authenticated
 * endpoint a tenant-less user must be able to reach. Identity is read straight
 * off the JWT, not {@code TenantContext}. Rejects a caller who already has a
 * tenant (a token that already carries {@code tenant_id}).
 */
@RestController
@RequestMapping("/api")
@Profile("security")
public class OnboardingController {

    private final TenantProvisioningService provisioningService;
    private final DemoConversionService demoConversionService;
    private final String tenantClaim;

    public OnboardingController(
        TenantProvisioningService provisioningService, DemoConversionService demoConversionService,
        @Value("${app.security.oidc.tenant-claim:tenant_id}") String tenantClaim
    ) {
        this.provisioningService = provisioningService;
        this.demoConversionService = demoConversionService;
        // Generic-OIDC claim mapping (app.security.oidc.tenant-claim): the
        // "already has a tenant" check below must read the same claim
        // TenantContextFilter reads. Blank falls back to the Keycloak default.
        this.tenantClaim = (tenantClaim == null || tenantClaim.isBlank())
            ? JwtClaimPaths.DEFAULT_TENANT_CLAIM : tenantClaim;
    }

    @PostMapping("/onboarding")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse onboard(@Valid @RequestBody OnboardingRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        String existingTenant = JwtClaimPaths.valueAsString(jwt, tenantClaim);
        if (existingTenant != null && !existingTenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already linked to an organization.");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Your sign-in provider did not share an email address, which is required to create an organization."
            );
        }
        SignupResponse response = provisioningService.onboardExistingUser(
            jwt.getSubject(), email,
            jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"),
            request.orgName(), request.newsOptInOrDefault()
        );
        // Same posture as TenantSignupController: strictly after success,
        // internally best-effort, never able to fail the onboarding. This is
        // the HIGHEST-value conversion edge - a sandbox visitor who taps
        // "continue with Google" lands here, not on /api/signup - so missing
        // this call site was most of why converted_tenant_id never got set.
        demoConversionService.recordRegistrationConversion(request.demoTokenOrNull(), response.tenantId());
        return response;
    }
}
