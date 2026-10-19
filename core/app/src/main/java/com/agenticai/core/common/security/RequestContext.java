package com.agenticai.core.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request context: metadata about the HTTP request (path, method, etc.) for routing
 * fraud detection decisions.
 */
public class RequestContext {
    public final String path;
    public final String method;
    public final String contentType;
    public final boolean isAuthEndpoint;
    public final boolean isApiEndpoint;
    public final boolean isBillingEndpoint;
    public final boolean isHighRiskEndpoint;

    public RequestContext(String path, String method, String contentType) {
        this.path = path;
        this.method = method;
        this.contentType = contentType != null ? contentType : "application/json";
        this.isAuthEndpoint = path.contains("/auth/") || path.contains("/login") || path.contains("/signup");
        this.isApiEndpoint = path.startsWith("/api/");
        this.isBillingEndpoint = path.contains("/billing/") || path.contains("/payment/") || path.contains("/checkout/");
        this.isHighRiskEndpoint = isAuthEndpoint || isBillingEndpoint;
    }

    public static RequestContext fromRequest(HttpServletRequest req) {
        return new RequestContext(req.getRequestURI(), req.getMethod(), req.getContentType());
    }

    /**
     * Determine if this endpoint should be subject to fraud detection checks.
     */
    public boolean shouldCheckForFraud() {
        // Exclude healthchecks, actuators, and internal endpoints
        if (path.startsWith("/actuator") || path.equals("/health")) {
            return false;
        }

        // Include all auth, API, and billing endpoints
        return isAuthEndpoint || isApiEndpoint || isBillingEndpoint;
    }

    @Override
    public String toString() {
        return "RequestContext{" +
                "path='" + path + '\'' +
                ", method='" + method + '\'' +
                ", riskLevel='" + (isHighRiskEndpoint ? "HIGH" : isApiEndpoint ? "MEDIUM" : "LOW") + '\'' +
                '}';
    }
}
