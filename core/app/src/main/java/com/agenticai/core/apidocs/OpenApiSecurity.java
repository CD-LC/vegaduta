package com.agenticai.core.apidocs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.AntPathMatcher;

/**
 * Decides which security requirement an operation gets in the generated OpenAPI document.
 *
 * <p>The public routes below are an EXPLICIT allowlist mirroring every
 * {@code requestMatchers(...).permitAll()} line of
 * {@code core/security/.../SecurityConfig.java} (the main chain). They are not derived at runtime
 * because building the real filter chain needs a Spring context, a JWT decoder and a database,
 * and the generator is deliberately a pure, context-free reflection tool. Drift is guarded by
 * {@code OpenApiSpecGeneratorTest#publicAllowlistMirrorsSecurityConfig}, which parses the
 * SecurityConfig source and fails when the two lists disagree.
 *
 * <p>Everything else is {@code bearerAuth} (a Keycloak-issued JWT), except two families that
 * authenticate with a long-lived {@code vmcp_} grant key instead of (or as well as) a JWT:
 * {@code /api/dev/**} and {@code /mcp/**} (grant key only, see {@code mcpServerFilterChain}) and
 * {@code /v1/**} (the OpenAI-compatible surface: JWT or grant key). Every other authenticated
 * {@code /api/**} route also accepts a tenant API key ({@code Bearer vdak_...}, see {@code
 * ApiKeyAuthFilter} in the main chain) as an alternative to the JWT.
 *
 * <p>"Public" here means no bearer token is needed at the gateway. Webhook, device and claim
 * endpoints still verify their own credential (signature, device token, pairing code) inside the
 * handler; the generator flags that in the operation description.
 */
final class OpenApiSecurity {

    static final String BEARER = "bearerAuth";
    static final String API_KEY = "apiKeyAuth";
    static final String TENANT_API_KEY = "tenantApiKey";

    /** One permitAll rule. {@code method == null} means every HTTP method. */
    record PublicRule(String method, String pattern) {
    }

    // Mirror of SecurityConfig permitAll() lines (minus CORS preflight OPTIONS and /actuator/**,
    // which are not controller routes). Keep in the same order as SecurityConfig.
    static final List<PublicRule> PUBLIC_RULES = List.of(
        new PublicRule("GET", "/api/system/status/live"),
        new PublicRule("GET", "/api/public/status"),
        new PublicRule("POST", "/api/public/landing-event"),
        new PublicRule(null, "/webhook/**"),
        new PublicRule(null, "/api/billing/webhook/**"),
        new PublicRule("GET", "/oauth/google/callback"),
        new PublicRule("GET", "/oauth/x/callback"),
        new PublicRule("GET", "/oauth/atlassian/callback"),
        new PublicRule("GET", "/oauth/mcp/*/callback"),
        new PublicRule("GET", "/oauth/mcp/remote/*"),
        new PublicRule("POST", "/api/devices/claim"),
        new PublicRule("POST", "/api/devices/poll"),
        new PublicRule("POST", "/api/devices/commands/*/result"),
        new PublicRule("POST", "/api/devices/screen-state"),
        new PublicRule("POST", "/api/devices/notifications"),
        new PublicRule("POST", "/api/devices/app-usage"),
        new PublicRule("POST", "/api/devices/sms"),
        new PublicRule("POST", "/api/signup"),
        new PublicRule("GET", "/api/auth/social-providers"),
        new PublicRule(null, "/api/demo/**"),
        new PublicRule(null, "/api/share/agents/**"),
        new PublicRule(null, "/api/share/workflows/**"),
        new PublicRule(null, "/api/public/apps/**"),
        new PublicRule(null, "/api/public/email/unsubscribe"),
        new PublicRule("GET", "/api/public/workflow-forms/*"),
        new PublicRule("POST", "/api/public/workflow-forms/*"),
        new PublicRule("GET", "/api/share/branding/**"),
        new PublicRule("GET", "/api/templates/gallery"),
        new PublicRule("GET", "/api/templates/gallery/**"),
        new PublicRule("GET", "/api/plugins/catalog"),
        new PublicRule("GET", "/api/plugins/artifacts/**"),
        new PublicRule(null, "/api/commerce/feed/**"),
        new PublicRule("GET", "/.well-known/agent-card.json"),
        new PublicRule("GET", "/.well-known/agent.json"),
        new PublicRule("GET", "/.well-known/jwks.json"),
        new PublicRule("GET", "/api/directory/agents"),
        new PublicRule("GET", "/api/directory/agents/*"),
        new PublicRule(null, "/x402/*"),
        new PublicRule("GET", "/api/edge/config"),
        new PublicRule("GET", "/api/edge/manifest"),
        new PublicRule("GET", "/api/edge/tools"),
        new PublicRule("GET", "/api/edge/memory"),
        new PublicRule("POST", "/api/edge/tools/execute"),
        new PublicRule("POST", "/api/edge/turns"),
        new PublicRule(null, "/scim/v2/**"),
        new PublicRule("POST", "/api/tenant/comp-grant"),
        new PublicRule("POST", "/api/internal/app-builder/generate-files"),
        new PublicRule("POST", "/api/internal/app-builder/generate-image")
    );

    /**
     * The local-auth filter chain ({@code LocalAuthSecurityConfig}) is separate from the main
     * {@code SecurityConfig} the list above mirrors: everything under {@code /api/auth/local/**}
     * is permitAll EXCEPT these paths, which need a signed-in session (its {@code
     * BEARER_REQUIRED}). Keep the two in step.
     */
    private static final List<String> LOCAL_AUTH_BEARER_REQUIRED =
            List.of(
                    "/api/auth/local/change-password",
                    "/api/auth/local/unlock/methods",
                    "/api/auth/local/unlock/devices",
                    "/api/auth/local/unlock/devices/*",
                    "/api/auth/local/unlock/passkeys/*",
                    "/api/auth/local/unlock/recovery-codes");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private OpenApiSecurity() {
    }

    /** True when the (method, path-template) pair is a permitAll route. */
    static boolean isPublic(String httpMethod, String path) {
        if (MATCHER.match("/api/auth/local/**", path)) {
            for (String required : LOCAL_AUTH_BEARER_REQUIRED) {
                if (MATCHER.match(required, path)) {
                    return false;
                }
            }
            return true;
        }
        for (PublicRule rule : PUBLIC_RULES) {
            if (rule.method() != null && !rule.method().equalsIgnoreCase(httpMethod)) {
                continue;
            }
            // AntPathMatcher treats a "{id}" template segment as plain text matched by "*" / "**".
            if (MATCHER.match(rule.pattern(), path)) {
                return true;
            }
        }
        return false;
    }

    /** Grant-key surfaces ({@code /api/dev/**}, {@code /mcp/**}). */
    static boolean isGrantKeyOnly(String path) {
        return MATCHER.match("/api/dev/**", path) || MATCHER.match("/mcp/**", path);
    }

    /** OpenAI-compatible surface: a platform JWT or a grant key. */
    static boolean isJwtOrGrantKey(String path) {
        return MATCHER.match("/v1/**", path);
    }

    /**
     * The OpenAPI {@code security} array for an operation: an empty list means public. Each element
     * is one alternative requirement object.
     */
    static List<Object> requirementsFor(String httpMethod, String path) {
        List<Object> out = new ArrayList<>();
        if (isPublic(httpMethod, path)) {
            return out;
        }
        if (isGrantKeyOnly(path)) {
            out.add(requirement(API_KEY));
        } else if (isJwtOrGrantKey(path)) {
            out.add(requirement(BEARER));
            out.add(requirement(API_KEY));
        } else {
            out.add(requirement(BEARER));
            if (MATCHER.match("/api/**", path)) {
                out.add(requirement(TENANT_API_KEY));
            }
        }
        return out;
    }

    private static Map<String, Object> requirement(String scheme) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(scheme, List.of());
        return m;
    }
}
