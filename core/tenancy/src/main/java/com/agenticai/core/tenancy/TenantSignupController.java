package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.demo.DemoConversionService;
import com.agenticai.core.tenancy.dto.SignupRequest;
import com.agenticai.core.tenancy.dto.SignupResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/signup} - unauthenticated by necessity (nobody has a
 * token yet), so gated instead by {@link SignupRateLimiter} and normal
 * request validation. See {@code SecurityConfig}'s {@code /api/signup}
 * {@code permitAll()} rule.
 */
@RestController
@RequestMapping("/api")
@Profile("security")
public class TenantSignupController {

    private final TenantProvisioningService provisioningService;
    private final SignupRateLimiter rateLimiter;
    private final DemoConversionService demoConversionService;

    public TenantSignupController(
        TenantProvisioningService provisioningService,
        SignupRateLimiter rateLimiter,
        DemoConversionService demoConversionService
    ) {
        this.provisioningService = provisioningService;
        this.rateLimiter = rateLimiter;
        this.demoConversionService = demoConversionService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest servletRequest) {
        String clientIp = clientIp(servletRequest);
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw new SignupRateLimitExceededException("Too many signup attempts - please try again later.");
        }
        SignupResponse response = provisioningService.signup(request);
        // Strictly after a SUCCESSFUL signup (an exception above never reaches
        // this line), and internally best-effort: a junk/absent demoToken or a
        // bookkeeping failure logs and moves on rather than failing a
        // registration that already provisioned a tenant. This is the call
        // that finally sets demo_session.converted_tenant_id - the numerator
        // of the analytics dashboard's conversion rate.
        demoConversionService.recordRegistrationConversion(request.demoTokenOrNull(), response.tenantId());
        return response;
    }

    // request.getRemoteAddr() - now correctly resolves to the real client IP
    // as of 2026-07-26 via server.forward-headers-strategy=native +
    // server.tomcat.remoteip.trusted-proxies in application.yml (Tomcat's
    // RemoteIpValve trusts X-Forwarded-For only when the direct connection
    // is from Caddy/the internal Docker network, not from an arbitrary
    // external caller trying to spoof it) - same fix, same reasoning, as
    // DemoSessionController#clientIp's javadoc. No code change needed here.
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
