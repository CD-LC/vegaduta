package com.agenticai.core.agents.tools.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration of the {@code http_request} tool family under {@code app.tools.http-request.*}.
 *
 * <p>Two independent switches, both defaulting to today's behaviour:
 *
 * <ul>
 *   <li>{@code enabled} (default <b>true</b>): whether {@code http_request} is offered to agents at
 *       all. The tool already shipped, unconditionally, before this class existed (it is the n8n
 *       "HTTP Request" equivalent and workflows' {@code http} node shares its executor), so the
 *       default keeps it offered; an operator can now turn it off.
 *   <li>{@code safe-mode} (default <b>false</b>): the hardened profile. Off, the tool is
 *       byte-for-byte what it was: any method, {@code {{secret.NAME}}} references accepted
 *       anywhere, any public host. On, the tool splits into {@code http_request} (GET/HEAD/OPTIONS,
 *       classified READ like fetch_web_page) and {@code http_request_write} (POST/PUT/PATCH/DELETE,
 *       classified EGRESS + irreversible so the confirmation gate prompts), and {@link
 *       HttpRequestExecutor#executeSafe} enforces https-only, a host allow-list, body/response
 *       caps, a content-type allow-list, a header deny-list and credential injection only from a
 *       named vault secret. See {@link HttpRequestPolicy}.
 * </ul>
 *
 * <p>Per-agent host allow-lists have no home in the agent record without a migration, so they are
 * configuration: {@code agent-allowed-hosts} is {@code <agentUuid>=host,host;<agentUuid>=host}. A
 * global {@code allowed-hosts} list is the operator's ceiling. Both empty means any public host -
 * stated plainly because that is the honest posture of an unconfigured deployment, which is why
 * credentialed requests (below) refuse to run without one.
 *
 * <p>Single constructor on purpose (Spring picks it; see {@code InjectableConstructorTest}); every
 * {@code @Value} repeats its default so slim test contexts construct.
 */
@Component
public class HttpRequestSafetyConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestSafetyConfig.class);

    /** Verbs that read. Safe to retry; classified like {@code fetch_web_page}. */
    public static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Verbs that change state at the destination. Classified EGRESS + irreversible. */
    public static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final boolean enabled;
    private final boolean safeMode;
    private final boolean writeToolEnabled;
    private final boolean allowHttp;
    private final int maxRequestBodyBytes;
    private final int maxResponseBytes;
    private final int maxRedirects;
    private final List<String> allowedContentTypes;
    private final Set<String> deniedHeaders;
    private final List<String> globalAllowedHosts;
    private final Map<UUID, List<String>> agentAllowedHosts;
    private final boolean credentialsRequireAllowlist;

    public HttpRequestSafetyConfig(
            @Value("${app.tools.http-request.enabled:true}") boolean enabled,
            @Value("${app.tools.http-request.safe-mode:false}") boolean safeMode,
            @Value("${app.tools.http-request.write-tool-enabled:true}") boolean writeToolEnabled,
            @Value("${app.tools.http-request.allow-http:false}") boolean allowHttp,
            @Value("${app.tools.http-request.max-request-body-bytes:65536}")
                    int maxRequestBodyBytes,
            @Value("${app.tools.http-request.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${app.tools.http-request.max-redirects:3}") int maxRedirects,
            @Value(
                            "${app.tools.http-request.allowed-content-types:application/json,application/*+json,text/*,application/xml,application/*+xml,application/x-www-form-urlencoded}")
                    String allowedContentTypes,
            @Value(
                            "${app.tools.http-request.denied-headers:authorization,proxy-authorization,cookie,set-cookie,x-api-key,api-key,x-auth-token,x-access-token,x-forwarded-for,x-forwarded-host,x-forwarded-proto,x-real-ip,forwarded,via}")
                    String deniedHeaders,
            @Value("${app.tools.http-request.allowed-hosts:}") String allowedHosts,
            @Value("${app.tools.http-request.agent-allowed-hosts:}") String agentAllowedHosts,
            @Value("${app.tools.http-request.credentials-require-allowlist:true}")
                    boolean credentialsRequireAllowlist) {
        this.enabled = enabled;
        this.safeMode = safeMode;
        this.writeToolEnabled = writeToolEnabled;
        this.allowHttp = allowHttp;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.maxRedirects = maxRedirects;
        this.allowedContentTypes = HttpRequestPolicy.splitList(allowedContentTypes);
        this.deniedHeaders = Set.copyOf(HttpRequestPolicy.splitList(deniedHeaders));
        this.globalAllowedHosts = HttpRequestPolicy.splitList(allowedHosts);
        this.agentAllowedHosts = parseAgentHosts(agentAllowedHosts);
        this.credentialsRequireAllowlist = credentialsRequireAllowlist;
    }

    /**
     * Whether http_request is offered to agents at all. Default true (the tool pre-dates this
     * flag).
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether the hardened profile is active. Default false. */
    public boolean isSafeMode() {
        return safeMode;
    }

    /**
     * In safe mode, whether the state-changing {@code http_request_write} tool is offered next to
     * the read tool.
     */
    public boolean isWriteToolEnabled() {
        return writeToolEnabled;
    }

    /**
     * The policy for one agent and one tool instance.
     *
     * @param agentId the agent (may be null: only the operator allow-list applies)
     * @param methods the verbs this tool instance may send
     */
    public HttpRequestPolicy policyFor(UUID agentId, Set<String> methods) {
        List<String> agentHosts =
                agentId == null ? List.of() : agentAllowedHosts.getOrDefault(agentId, List.of());
        return new HttpRequestPolicy(
                methods,
                allowHttp,
                maxRequestBodyBytes,
                maxResponseBytes,
                maxRedirects,
                allowedContentTypes,
                deniedHeaders,
                globalAllowedHosts,
                agentHosts,
                credentialsRequireAllowlist);
    }

    /**
     * {@code uuid=host,host;uuid=host} - malformed entries are skipped with a warning, never fatal.
     */
    static Map<UUID, List<String>> parseAgentHosts(String raw) {
        Map<UUID, List<String>> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                if (!entry.isBlank()) {
                    log.warn(
                            "Ignoring malformed app.tools.http-request.agent-allowed-hosts entry (expected <agentUuid>=host,host)");
                }
                continue;
            }
            try {
                UUID agent = UUID.fromString(entry.substring(0, eq).strip());
                out.put(agent, HttpRequestPolicy.splitList(entry.substring(eq + 1)));
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Ignoring app.tools.http-request.agent-allowed-hosts entry with an invalid agent id");
            }
        }
        return out;
    }
}
