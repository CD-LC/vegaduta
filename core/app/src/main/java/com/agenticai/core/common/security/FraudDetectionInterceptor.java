package com.agenticai.core.common.security;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP request interceptor for fraud detection. Runs before request handlers to check for abuse
 * patterns and enforce fraud decisions.
 *
 * <p>Decision flow: 1. Extract client context (IP, User-Agent, TLS fingerprint, auth state) 2.
 * Check blacklists (fast path) 3. Analyze request patterns (rate limiting, auth abuse, VPN) 4.
 * Enforce decision: ALLOW (continue) / CHALLENGE (403 + nonce) / BLOCK (403) 5. Log abuse event
 * asynchronously
 *
 * <p><b>Tenant resolution (JAVA-005).</b> This used to read a {@code tenantId} request attribute
 * that nothing in the application ever sets, so it logged a WARN on every authenticated request and
 * skipped every check. The tenant and user now come from {@link TenantContext} via {@link
 * FraudTenantIds} - the same mapping {@link FraudAnalysisController} uses, so admin blacklist
 * entries match what is checked here. Requests with no tenant (public paths) are skipped quietly.
 *
 * <p><b>Enforcement.</b> Explicit IP / user blacklist entries are always enforced (BLOCK). The
 * behavioural heuristics (per-IP request-rate and auth-attempt counters) have never run in
 * production and their defaults (a CHALLENGE above 60 requests a minute per tenant and IP, a BLOCK
 * above 100) are below the traffic of an ordinary busy session or an office behind one NAT address,
 * and the CHALLENGE response needs an hCaptcha step no client implements. So by default they only
 * OBSERVE: the decision is logged as "would block" and the request proceeds. Set {@code
 * fraud-detection.enforce=true} to enforce them (tune {@code fraud-detection.api.*} first).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = FraudDetectionConfig.ENABLED_PROPERTY, havingValue = "true")
public class FraudDetectionInterceptor implements HandlerInterceptor {
    private final AbuseDetectionService abuseDetectionService;
    private final ObjectMapper objectMapper;
    private final boolean enforce;

    public FraudDetectionInterceptor(
            AbuseDetectionService abuseDetectionService,
            ObjectMapper objectMapper,
            @Value("${fraud-detection.enforce:false}") boolean enforce) {
        this.abuseDetectionService = abuseDetectionService;
        this.objectMapper = objectMapper;
        this.enforce = enforce;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws IOException {

        RequestContext requestCtx = RequestContext.fromRequest(req);

        // Skip fraud checks for certain endpoints
        if (shouldSkipFraudCheck(requestCtx.path)) {
            return true;
        }

        // Tenant and user from TenantContext (set by TenantContextFilter from
        // the verified JWT). The old code read request attributes nothing
        // ever set, so it skipped every request with a WARN (JAVA-005).
        UUID tenantId = FraudTenantIds.currentTenant().orElse(null);
        UUID userId = FraudTenantIds.currentUser().orElse(null);

        if (tenantId == null) {
            // Anonymous/tenant-less request (public endpoints): nothing to key on.
            log.debug("No tenant context for {} - fraud checks skipped", requestCtx.path);
            return true;
        }

        // Extract client context
        ClientContext clientCtx = ClientContext.fromRequest(req, tenantId, userId);

        // Analyze request for fraud
        FraudDecision decision;
        try {
            decision = abuseDetectionService.analyzeRequest(clientCtx, requestCtx);
        } catch (Exception e) {
            log.error("Error during fraud analysis: {}", clientCtx, e);
            // Fail open: don't block on errors
            return true;
        }

        // Explicit blacklist entries are administrative decisions and are always enforced;
        // everything else is a
        // heuristic that only enforces when fraud-detection.enforce=true (see the class javadoc).
        if (decision.isSuspicious() && !enforce && !isBlacklisted(clientCtx)) {
            log.warn(
                    "Fraud detection (observe-only) would {} {} {}",
                    decision,
                    requestCtx.method,
                    requestCtx.path);
            return true;
        }

        // Enforce decision
        if (decision == FraudDecision.BLOCK) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setHeader("X-Fraud-Status", "BLOCKED");
            res.setContentType("application/json");

            try {
                Map<String, Object> body =
                        Map.of(
                                "error", "fraud_detected_request_blocked",
                                "message",
                                        "Your request has been blocked due to suspicious activity. Please try again later.");
                res.getWriter().write(objectMapper.writeValueAsString(body));
            } catch (IOException e) {
                log.error("Error writing fraud block response", e);
            }

            log.warn("Request blocked by fraud detection: {} ({})", clientCtx, requestCtx);
            return false;
        }

        if (decision == FraudDecision.CHALLENGE) {
            String nonce =
                    abuseDetectionService.generateChallengeNonce(tenantId, userId, clientCtx.ip);

            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setHeader("X-Fraud-Challenge", "HCAPTCHA");
            res.setHeader("X-Challenge-Nonce", nonce);
            res.setHeader(
                    "X-Challenge-Expires",
                    String.valueOf(System.currentTimeMillis() + 5 * 60 * 1000)); // 5 minutes
            res.setContentType("application/json");

            try {
                Map<String, Object> body =
                        Map.of(
                                "error", "fraud_challenge_required",
                                "message", "Please verify you're not a bot",
                                "challenge_nonce", nonce,
                                "challenge_type", "HCAPTCHA",
                                "expires_in", 300 // seconds
                                );
                res.getWriter().write(objectMapper.writeValueAsString(body));
            } catch (IOException e) {
                log.error("Error writing fraud challenge response", e);
            }

            log.info("Challenge issued by fraud detection: {} (nonce: {})", clientCtx, nonce);
            return false;
        }

        // Decision is ALLOW - proceed with request
        // Store challenge pass token if provided (for tracking verified challenges)
        String challengePass = req.getHeader("X-Challenge-Pass");
        if (challengePass != null) {
            req.setAttribute("fraudChallengePassed", true);
        }

        return true;
    }

    private boolean isBlacklisted(ClientContext clientCtx) {
        try {
            return abuseDetectionService.isIpBlacklisted(clientCtx.tenantId, clientCtx.ip)
                    || (clientCtx.userId != null
                            && abuseDetectionService.isUserBlacklisted(
                                    clientCtx.tenantId, clientCtx.userId));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Determine if this request should be skipped from fraud checks. */
    private boolean shouldSkipFraudCheck(String path) {
        // Always skip health checks and actuators
        if (path.startsWith("/actuator/")
                || path.equals("/health")
                || path.equals("/health/live")) {
            return true;
        }

        // Skip internal/admin endpoints (they have their own auth)
        if (path.startsWith("/api/internal/") && !path.startsWith("/api/internal/abuse/")) {
            return true;
        }

        // Skip static resources
        if (path.startsWith("/static/")
                || path.startsWith("/assets/")
                || path.endsWith(".css")
                || path.endsWith(".js")
                || path.endsWith(".jpg")
                || path.endsWith(".png")
                || path.endsWith(".gif")) {
            return true;
        }

        return false;
    }
}
