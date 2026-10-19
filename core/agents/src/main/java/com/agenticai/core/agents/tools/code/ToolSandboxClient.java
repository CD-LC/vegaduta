package com.agenticai.core.agents.tools.code;

import com.agenticai.core.agents.tools.code.dto.SandboxExecuteRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxExecuteResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxHealthResponse;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import com.agenticai.core.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thin server-to-server client over the tool-sandbox microservice - the ONLY place in core that
 * touches tenant-authored source code, and it does nothing with it but put it in a request body.
 *
 * <p>Same shape and posture as {@code CodeIntelClient}: a {@link RestClient} built from the
 * Spring-managed builder with a {@code baseUrl}, and two headers resolved fresh on every call
 * rather than cached at construction - {@code X-Internal-Token} (the shared secret) and {@code
 * X-Tenant-Id} (the current {@link TenantContext}'s tenant, so the sandbox can scope its own
 * per-tenant rate limits and workdirs). Resolving per call is what makes a rotated token or a
 * different tenant on the next request Just Work.
 *
 * <p>Only created when {@code tool-sandbox.enabled=true}, so with the feature off this bean is
 * absent and {@code CustomCodeToolService}'s {@code ObjectProvider<ToolSandboxClient>} yields
 * nothing - see {@link CustomCodeToolsDisabledException}.
 *
 * <p><strong>Error posture, which the whole feature depends on:</strong> this class deliberately
 * does NOT swallow failures into an error-JSON string the way {@code CodeIntelClient} does. There,
 * every response went straight to a model as opaque text, so a JSON error blob was the right shape.
 * Here the responses are typed and drive real persistence decisions (a REJECTED row, a refused
 * enable), so "the sandbox was down" MUST be distinguishable from "the sandbox says this code is
 * bad" - conflating them would silently mark a perfectly good tool rejected during a deploy blip.
 * Transport/auth/non-2xx therefore becomes {@link ToolSandboxUnavailableException} (-> 502); a real
 * verdict is always a 200 body.
 */
@Component
@ConditionalOnProperty(prefix = "tool-sandbox", name = "enabled", havingValue = "true")
public class ToolSandboxClient {

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final ToolSandboxProperties properties;

    public ToolSandboxClient(
            RestClient.Builder restClientBuilder, ToolSandboxProperties properties) {
        this.properties = properties;
        // Plain builder + baseUrl, no custom request factory - same reasoning
        // CodeIntelClient documents (stay version-agnostic across Spring Boot's
        // evolving ClientHttpRequestFactory API). The shared builder's own
        // read timeout already bounds these calls; tool-sandbox.*-timeout-millis
        // are carried for the sandbox's own use and for a future shared
        // RestClient.Builder customizer.
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * Compile/parse check. Returns the sandbox's verdict - {@code valid=false} is a normal return,
     * NOT an exception. Throws only if the sandbox itself could not be reached or answered non-2xx.
     */
    public SandboxValidateResponse validate(
            CustomCodeToolLanguage language, String toolName, String source) {
        SandboxValidateRequest request =
                new SandboxValidateRequest(language.wireValue(), toolName, source);
        return post("/v1/validate", request, SandboxValidateResponse.class);
    }

    /**
     * Runs one program to completion inside the sandbox. Same error posture as {@link #validate}:
     * {@code ok=false} (a throw, a timeout, an OOM in the tenant's program) comes back as a normal
     * 200 body.
     */
    public SandboxExecuteResponse execute(SandboxExecuteRequest request) {
        return post("/v1/execute", request, SandboxExecuteResponse.class);
    }

    /** Liveness plus the sandbox's advertised languages/seccomp profile. */
    public SandboxHealthResponse health() {
        try {
            return restClient
                    .get()
                    .uri("/health")
                    .header(TENANT_HEADER, tenantId())
                    .header(TOKEN_HEADER, properties.getInternalToken())
                    .retrieve()
                    .body(SandboxHealthResponse.class);
        } catch (RestClientResponseException e) {
            throw unavailable(
                    "GET /health", e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw unavailable("GET /health", 0, e.getMessage(), e);
        }
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            return restClient
                    .post()
                    .uri(path)
                    .header(TENANT_HEADER, tenantId())
                    .header(TOKEN_HEADER, properties.getInternalToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException e) {
            // Non-2xx. Never a verdict (those are 200) - always a real fault:
            // wrong/missing internal token, sandbox out of capacity, a proxy
            // in between, or a version skew in the wire contract.
            throw unavailable(
                    "POST " + path, e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // Connection refused, DNS, read timeout, deserialization failure.
            throw unavailable("POST " + path, 0, e.getMessage(), e);
        }
    }

    private static ToolSandboxUnavailableException unavailable(
            String call, int status, String detail, Throwable cause) {
        String d = detail == null ? "" : detail;
        if (d.length() > 500) {
            d = d.substring(0, 500);
        }
        return new ToolSandboxUnavailableException(
                "The code sandbox is unavailable ("
                        + call
                        + (status > 0 ? " -> HTTP " + status : " -> no response")
                        + (d.isBlank() ? "" : ": " + d)
                        + ").",
                cause);
    }

    private static String tenantId() {
        // Empty rather than absent when there is no tenant on the thread (a
        // platform/system-initiated call): the header shape stays constant so
        // the sandbox never has to distinguish "missing" from "blank".
        return TenantContext.current().map(TenantContext::getTenantId).orElse("");
    }
}
