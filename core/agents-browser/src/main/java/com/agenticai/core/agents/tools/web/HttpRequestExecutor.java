package com.agenticai.core.agents.tools.web;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.agents.tools.x402.X402PaymentService;
import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Performs one arbitrary-method HTTP call on behalf of an agent turn or a workflow run - the engine
 * behind the {@code http_request} built-in tool ({@link HttpRequestToolCallback}) and the {@code
 * http} workflow node. The single biggest connector-catalog gap versus n8n/Zapier before this
 * existed was "call this REST API with my key": {@link WebPageFetcher} only ever does an
 * unauthenticated GET and reduces the answer to prose, so every JSON API, webhook, or authenticated
 * endpoint needed a bespoke MCP server.
 *
 * <p><b>SSRF posture (SEC-025/027):</b> the URL comes straight out of an LLM tool call or a
 * tenant-authored workflow config. Every request goes through {@link SafeHttpClients} built on the
 * shared {@link SsrfGuard}: the host is resolved ONCE at connect time, every address is classified,
 * and the socket goes to one of the checked addresses (no DNS-rebinding window), with no proxy.
 * Redirects are OFF in the client and followed here by hand with a bounded hop count; every hop is
 * re-validated with {@link SsrfGuard#resolveRedirect}. When a hop changes origin, credential
 * headers ({@code Authorization}, {@code Proxy-Authorization}, {@code Cookie}, {@code X-PAYMENT},
 * the attestation JWT) and EVERY header whose value carried a resolved vault secret are dropped,
 * and a body carrying a secret is never re-sent to another origin.
 *
 * <p><b>Vault secrets (SEC-006):</b> header, query and body values may carry {@code
 * {{secret.NAME}}} references (the platform's canonical form, see {@link SecretReferenceResolver})
 * or the {@code {{secret:NAME}}} spelling the tool description advertises. A reference resolves
 * ONLY when the secret's per-secret allowed hosts (V365.4) match the request's destination host - a
 * secret with none never resolves here. Synthetic/untrusted callers (share visitors, workflow share
 * runs, webhook triggers, inbound channels - see {@link #isUntrustedSubject}) resolve TENANT-scoped
 * secrets only, still subject to the allow-list, and never trigger an x402 payment. Missing,
 * revoked and not-allowed references all fail with ONE generic message, so the tool cannot be used
 * to enumerate secret names. Every string that travels back toward the model or the run log is
 * passed through {@link SecretRedactor}.
 *
 * <p><b>Workflow templating (SEC-007):</b> run input reaches this class as {@link
 * HttpRequestSpec#templateValues()}, never pre-substituted. Templates are rendered in ONE pass over
 * the AUTHOR's text: a placeholder is classified (vault reference, input field, or literal) from
 * the author's text alone, and substituted input values are never re-scanned - so input such as
 * {@code {{secret.STRIPE_KEY}}}, or two inputs that only form a reference once concatenated, are
 * sent literally.
 *
 * <p><b>Logging (SEC-049):</b> URLs are logged via {@link #loggableUrl} - no query string, no
 * fragment, no userinfo - and header values never.
 *
 * <p><b>Caps:</b> timeout clamped to at most {@value #MAX_TIMEOUT_SECONDS}s, raw response read
 * capped at {@value #MAX_RESPONSE_BYTES} bytes, body text clipped to {@code maxChars} (default
 * {@value #DEFAULT_MAX_BODY_CHARS}, hard max {@value #HARD_MAX_BODY_CHARS}). Binary responses are
 * summarised, not decoded.
 *
 * <p><b>Pipeline:</b> the work is a chain of stages, each in its own class: {@link
 * HttpRequestValidation} (method, clamps, headers, body), {@link HttpSecretResolution} (vault
 * references and templating), {@link HttpHostPolicy} (SSRF guard, allow-list, numeric-host and
 * https rules), {@link HttpSendStage} (the pinned-DNS network call), {@link HttpRedirectStage}
 * (hop rules and cross-origin credential stripping) and {@link HttpResponseReader} (read, clip,
 * redact); {@link X402PaymentAttempt} is the isolated 402 payment step. This class sequences them.
 */
@Component
public class HttpRequestExecutor {

    public static final int DEFAULT_TIMEOUT_SECONDS = 15;
    public static final int MAX_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_BODY_CHARS = 20_000;
    public static final int HARD_MAX_BODY_CHARS = 60_000;
    static final int MIN_MAX_BODY_CHARS = 200;
    static final String TRUNCATION_MARKER = "\n[truncated]";

    /**
     * The one message for every missing / revoked / not-allowed secret reference (no name
     * enumeration).
     */
    static final String SECRET_NOT_AVAILABLE = HttpSecretResolution.SECRET_NOT_AVAILABLE;

    static final Set<String> ALLOWED_METHODS = HttpRequestValidation.ALLOWED_METHODS;

    private final ObjectProvider<AgentAttestationService> agentAttestation;

    // The pipeline stages (see the class javadoc): validate, resolve secrets, host policy, send,
    // redirect, read and redact; x402 payment is its own isolated attempt.
    private final HttpSecretResolution secrets;
    private final HttpResponseReader reader;
    private final X402PaymentAttempt x402;
    private final HttpSendStage sender;

    private volatile SsrfGuard guardOverride;

    public HttpRequestExecutor(
            ObjectProvider<SecretReferenceResolver> secretReferenceResolver,
            ObjectProvider<SecretRedactor> secretRedactor,
            ObjectProvider<AgentAttestationService> agentAttestation,
            ObjectProvider<X402PaymentService> x402Payments) {
        this.agentAttestation = agentAttestation;
        this.secrets = new HttpSecretResolution(secretReferenceResolver);
        this.reader = new HttpResponseReader(secretRedactor);
        this.x402 = new X402PaymentAttempt(x402Payments, secretReferenceResolver);
        this.sender = new HttpSendStage(this::guard);
    }

    /**
     * Optional (slim/test contexts and the workflow node never need it): the {@code
     * app.tools.http-request.*} configuration. Carried here, by setter, so {@code
     * AgentIntegrationToolResolver} - which already holds this bean and whose constructors are
     * shared with tests - can read the safe-mode switch without a new constructor parameter.
     */
    private volatile HttpRequestSafetyConfig safetyConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSafetyConfig(HttpRequestSafetyConfig safetyConfig) {
        this.safetyConfig = safetyConfig;
    }

    /**
     * Null when no configuration bean exists (legacy behaviour: the tool is offered, unhardened).
     */
    public HttpRequestSafetyConfig safetyConfig() {
        return safetyConfig;
    }

    /**
     * Executes {@code spec} for a call initiated by {@code userId} (the subject used to resolve
     * USER-scoped vault secrets; null resolves TENANT-scoped secrets only). Throws {@link
     * WebPageFetchException} with a person-readable, secret-free reason on EVERY failure path -
     * never a raw IOException - so both consumers can relay "Could not complete GET https://...:
     * <reason>" verbatim.
     */
    public HttpRequestResult execute(HttpRequestSpec spec, String userId) {
        if (spec == null) {
            throw new WebPageFetchException("no request was given");
        }
        String method = HttpRequestValidation.normaliseMethod(spec.method());
        int maxChars = HttpRequestValidation.clampMaxChars(spec.maxChars());
        Duration timeout = Duration.ofSeconds(HttpRequestValidation.clampTimeout(spec.timeoutSeconds()));
        TenantContext ctx = TenantContext.current().orElse(null);
        boolean untrusted = HttpRequestValidation.untrustedCaller(userId, ctx);
        // Synthetic callers own no USER-scoped secrets; resolve as "no user" so
        // only TENANT-scoped, destination-allow-listed secrets can ever apply.
        String vaultSubject = untrusted ? null : userId;
        Map<String, String> values = spec.templateValues();
        SsrfGuard guard = guard();

        Set<String> resolvedValues = new LinkedHashSet<>();
        try {
            // ---- 1. Validate: URL input-templated, never secret-bearing; destination host ----
            String baseUrl =
                    HttpSecretResolution.render(
                            spec.url() == null ? "" : spec.url().strip(), values, null);
            String destinationHost = HttpHostPolicy.validate(guard, baseUrl).host();

            // ---- 2. Resolve secrets: vault references -> plaintext, bound to the destination ----
            HttpSecretResolution.SecretSink sink =
                    secrets.sink(vaultSubject, destinationHost, resolvedValues);
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : spec.headers().entrySet()) {
                // Keys are never expanded - see SecretReferenceResolver#resolveAll.
                headers.put(entry.getKey(), HttpSecretResolution.render(entry.getValue(), values, sink));
            }
            Map<String, String> query = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : spec.query().entrySet()) {
                query.put(entry.getKey(), HttpSecretResolution.render(entry.getValue(), values, sink));
            }
            String body = HttpSecretResolution.render(spec.body(), values, sink);
            if (sink.failed) {
                throw new WebPageFetchException(SECRET_NOT_AVAILABLE);
            }

            // ---- 3. Host policy on the final URL (SSRF guard) ----
            String fullUrl = HttpRequestValidation.appendQuery(baseUrl, query);
            URI current = HttpHostPolicy.validate(guard, fullUrl).uri();

            // ---- 4. Headers and body ----
            Map<String, String> requestHeaders = HttpRequestValidation.checkedHeaders(headers, null);
            String bodyType = HttpRequestValidation.bodyType(spec);
            String requestBody = body == null || body.isBlank() ? null : body;
            if (requestBody != null && "form".equals(bodyType)) {
                requestBody = HttpRequestValidation.formEncodeIfJsonObject(requestBody);
            }
            if (requestBody != null
                    && !HttpRequestValidation.containsHeader(requestHeaders, "content-type")) {
                requestHeaders.put("Content-Type", HttpRequestValidation.defaultContentType(bodyType));
            }
            HttpRequestValidation.addDefaultHeaders(
                    requestHeaders, "application/json, text/*;q=0.9, */*;q=0.5");

            // KYA attestation - a signed JWT asserting which tenant/agent initiated this call,
            // verifiable against the deployment's /.well-known/jwks.json. Additive only: absent
            // when signing is off, never overwrites a caller-supplied header.
            addAttestation(requestHeaders, ctx);

            // ---- 5. Send, following redirects by hand ----
            String hopMethod = method;
            String hopBody = requestBody;
            boolean paymentAttempted = false;
            for (int hop = 0; hop <= HttpRedirectStage.MAX_REDIRECTS; hop++) {
                RawResponse response = send(current, hopMethod, requestHeaders, hopBody, timeout);
                int status = response.status();
                Optional<String> location = response.header("Location");
                if (HttpRedirectStage.isRedirect(status, location)) {
                    URI next = HttpRedirectStage.target(guard, current, location.get());
                    if (HttpRedirectStage.followsAsGet(status, hopMethod)) {
                        hopMethod = "GET";
                        hopBody = null;
                    }
                    if (!SsrfGuard.sameOrigin(current, next)) {
                        HttpRedirectStage.stripCrossOriginCredentials(requestHeaders, resolvedValues);
                        if (hopBody != null
                                && HttpRedirectStage.containsAny(hopBody, resolvedValues)) {
                            throw new WebPageFetchException(
                                    "the server redirected a request carrying a vault secret to a different site - not followed");
                        }
                    }
                    current = next;
                    continue;
                }
                // ---- x402: an HTTP 402 carrying a payment requirement the
                // tenant's own wallet can afford, to an allow-listed payee,
                // within the tenant's budget, is PAID and retried once with an
                // X-PAYMENT header. Anything else falls through to the normal
                // response below - the model still sees the 402 body.
                if (status == 402 && !paymentAttempted && !untrusted) {
                    paymentAttempted = true;
                    String paymentHeader =
                            x402.tryBuildPaymentHeader(
                                    response,
                                    userId,
                                    ctx == null ? null : ctx.getTenantId(),
                                    resolvedValues);
                    if (paymentHeader != null) {
                        requestHeaders.put("X-PAYMENT", paymentHeader);
                        response = send(current, hopMethod, requestHeaders, hopBody, timeout);
                    }
                }
                // ---- 6. Read and redact ----
                return reader.redact(reader.readResult(response, current, maxChars), resolvedValues);
            }
            throw new WebPageFetchException(
                    "the server redirected more than " + HttpRedirectStage.MAX_REDIRECTS + " times");
        } catch (WebPageFetchException e) {
            // Belt and braces: the reasons above are constant text, but the
            // redirect validator can quote a Location, so scrub anyway.
            throw new WebPageFetchException(reader.redactText(e.getMessage(), resolvedValues), e);
        }
    }

    /** KYA attestation header, when signing is on and the caller set none. */
    private void addAttestation(Map<String, String> requestHeaders, TenantContext ctx) {
        AgentAttestationService attestation = agentAttestation.getIfAvailable();
        if (attestation != null
                && !HttpRequestValidation.containsHeader(
                        requestHeaders, AgentAttestationService.HEADER_NAME)) {
            attestation
                    .mint(
                            ctx == null ? null : ctx.getTenantId(),
                            ctx == null ? null : ctx.getSubject(),
                            null)
                    .ifPresent(jwt -> requestHeaders.put(AgentAttestationService.HEADER_NAME, jwt));
        }
    }

    // ------------------------------------------------------------------
    // Safe mode (app.tools.http-request.safe-mode) - the hardened profile.
    // ------------------------------------------------------------------

    /**
     * The hardened sibling of {@link #execute(HttpRequestSpec, String)}, used by the safe-mode
     * {@code http_request} / {@code http_request_write} tools. Same SSRF guard as {@link #execute}
     * (the shared {@link SsrfGuard} on the URL and on every redirect hop, redirects followed by
     * hand, and the {@link SafeHttpClients} pinned-DNS client - see the class javadoc), plus what
     * {@code policy} adds:
     *
     * <ul>
     *   <li>https only (unless {@code allowHttp}), re-checked on every hop so a redirect cannot
     *       downgrade;
     *   <li>the host allow-lists, checked BEFORE DNS on the first URL (a disallowed name is never
     *       resolved, so DNS cannot carry data out) and on every redirect hop;
     *   <li>no vault reference anywhere in the url, headers, query or body - a prompt-injected
     *       {@code {{secret.X}}} cannot be smuggled to an arbitrary host. The only way a secret
     *       reaches the request is {@code credential}: the model names it, this method resolves and
     *       injects it;
     *   <li>the credential header is dropped on any redirect that leaves the original origin
     *       (scheme, host or port) and a credentialed request needs a configured allow-list at all;
     *   <li>a header deny-list for what the model may set directly, a request-body byte cap, a
     *       request and response content-type allow-list, a response byte cap with an explicit
     *       truncation marker, and a bounded redirect count;
     *   <li>no x402 auto-payment (a 402 is just returned) and no KYA attestation unless the
     *       destination is on an allow-list - both would otherwise act on, or disclose identity to,
     *       a host the model picked.
     * </ul>
     *
     * Failures are the same person-readable {@link WebPageFetchException}s; every message and
     * result is passed through the secret redactor with the credential's value (and its Base64
     * form) first.
     */
    public HttpRequestResult executeSafe(
            HttpRequestSpec spec,
            String userId,
            HttpRequestPolicy policy,
            HttpRequestCredential credential) {
        Objects.requireNonNull(policy, "policy");
        if (spec == null) {
            throw new WebPageFetchException("no request was given");
        }
        String method = HttpRequestValidation.normaliseMethod(spec.method());
        if (!policy.methodAllowed(method)) {
            throw new WebPageFetchException(
                    "HTTP method "
                            + method
                            + " is not available through this tool (it sends "
                            + String.join(", ", new TreeSet<>(policy.allowedMethods()))
                            + " only)");
        }
        int maxChars = HttpRequestValidation.clampMaxChars(spec.maxChars());
        Duration timeout = Duration.ofSeconds(HttpRequestValidation.clampTimeout(spec.timeoutSeconds()));

        if (HttpSecretResolution.hasSecretReference(spec.url())
                || HttpSecretResolution.anyValueHasSecretReference(spec.headers())
                || HttpSecretResolution.anyValueHasSecretReference(spec.query())
                || HttpSecretResolution.hasSecretReference(spec.body())) {
            throw new WebPageFetchException(
                    "vault secret references ({{secret.NAME}}) are not accepted here - "
                            + "pass the secret's NAME in the 'auth' parameter instead");
        }

        Set<String> resolvedValues = new LinkedHashSet<>();
        try {
            // ---- 1. URL: allow-list before DNS, then the shared SSRF guard, then scheme ----
            String fullUrl =
                    HttpRequestValidation.appendQuery(
                            spec.url() == null ? "" : spec.url().strip(), spec.query());
            HttpHostPolicy.checkHostBeforeResolution(policy, fullUrl);
            HttpHostPolicy.refuseNonCanonicalNumericHost(fullUrl);
            SsrfGuard guard = guard();
            URI current = HttpHostPolicy.validate(guard, fullUrl).uri();
            HttpHostPolicy.checkHop(policy, current);
            URI origin = current;

            // ---- 2. Named credential -> header value (never from the prompt), bound to the
            // validated destination host like every other vault read (SEC-006) ----
            String credentialHeader = null;
            String credentialValue = null;
            if (credential != null) {
                if (policy.credentialsRequireAllowlist() && !policy.allowlistConfigured()) {
                    throw new WebPageFetchException(
                            "credentialed requests are only sent to allow-listed hosts and no "
                                    + "allow-list is configured for this agent - ask an administrator to allow-list the API's host");
                }
                if (HttpRequestValidation.FORBIDDEN_HEADERS.contains(
                        credential.headerName().toLowerCase(Locale.ROOT))) {
                    throw new WebPageFetchException(
                            "the '"
                                    + credential.headerName()
                                    + "' header cannot be set by a request");
                }
                String secret =
                        secrets.resolveCredentialSecret(
                                credential.secretName(), userId, origin.getHost(), resolvedValues);
                credentialHeader = credential.headerName();
                credentialValue =
                        switch (credential.type()) {
                            case BEARER -> "Bearer " + secret;
                            case BASIC -> {
                                String encoded =
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        secret.getBytes(StandardCharsets.UTF_8));
                                resolvedValues.add(encoded);
                                yield "Basic " + encoded;
                            }
                            case HEADER -> secret;
                        };
            }

            // ---- 3. Headers ----
            Map<String, String> requestHeaders =
                    HttpRequestValidation.checkedHeaders(spec.headers(), policy);

            // ---- 4. Body ----
            String bodyType = HttpRequestValidation.bodyType(spec);
            String requestBody = spec.body() == null || spec.body().isBlank() ? null : spec.body();
            if (requestBody != null) {
                if (HttpRequestSafetyConfig.READ_METHODS.contains(method)) {
                    throw new WebPageFetchException("a request body is not allowed with " + method);
                }
                if ("form".equals(bodyType)) {
                    requestBody = HttpRequestValidation.formEncodeIfJsonObject(requestBody);
                }
                if (requestBody.getBytes(StandardCharsets.UTF_8).length
                        > policy.maxRequestBodyBytes()) {
                    throw new WebPageFetchException(
                            "the request body is larger than "
                                    + policy.maxRequestBodyBytes()
                                    + " bytes");
                }
                String explicitType = HttpRequestValidation.headerValue(requestHeaders, "content-type");
                if (explicitType != null && !policy.contentTypeAllowed(explicitType)) {
                    throw new WebPageFetchException(
                            "the request content type '"
                                    + explicitType
                                    + "' is not allowed on this tool");
                }
                if (explicitType == null) {
                    requestHeaders.put(
                            "Content-Type", HttpRequestValidation.defaultContentType(bodyType));
                }
            }
            HttpRequestValidation.addDefaultHeaders(requestHeaders, "application/json, text/*;q=0.9");
            // KYA attestation tells the destination which tenant/agent called; only to destinations
            // an operator or the agent's allow-list named, never to a host the model improvised.
            if (policy.allowlistConfigured()) {
                addAttestation(requestHeaders, TenantContext.current().orElse(null));
            }
            if (credentialHeader != null) {
                final String credentialName = credentialHeader;
                requestHeaders.keySet().removeIf(key -> key.equalsIgnoreCase(credentialName));
                requestHeaders.put(credentialHeader, credentialValue);
            }

            // ---- 5. Send, following redirects by hand: every hop re-runs allow-list, SSRF and
            // scheme ----
            String hopMethod = method;
            String hopBody = requestBody;
            for (int hop = 0; hop <= policy.maxRedirects(); hop++) {
                RawResponse response = send(current, hopMethod, requestHeaders, hopBody, timeout);
                int status = response.status();
                if (HttpRedirectStage.isRedirect(status, response.header("Location"))) {
                    if (hop == policy.maxRedirects()) {
                        break;
                    }
                    String location = response.header("Location").orElse(null);
                    URI target = HttpRedirectStage.resolveUnvalidated(current, location);
                    HttpHostPolicy.checkHostBeforeResolution(policy, target.toString());
                    HttpHostPolicy.refuseNonCanonicalNumericHost(target.toString());
                    URI next = HttpRedirectStage.target(guard, current, location);
                    HttpHostPolicy.checkHop(policy, next);
                    if (credentialHeader != null && !HttpHostPolicy.sameOrigin(origin, next)) {
                        // The credential belongs to the origin it was sent to - never follow it
                        // elsewhere.
                        final String credentialName = credentialHeader;
                        requestHeaders
                                .keySet()
                                .removeIf(key -> key.equalsIgnoreCase(credentialName));
                    }
                    if (!SsrfGuard.sameOrigin(current, next)) {
                        // SEC-025, same as execute(): credential/attestation headers never follow a
                        // hop to another origin.
                        HttpRedirectStage.stripCrossOriginCredentials(requestHeaders, resolvedValues);
                    }
                    current = next;
                    if (HttpRedirectStage.followsAsGet(status, hopMethod)) {
                        hopMethod = "GET";
                        hopBody = null;
                    }
                    continue;
                }
                return reader.redact(
                        reader.readResultSafe(response, current, maxChars, policy), resolvedValues);
            }
            throw new WebPageFetchException(
                    "the server redirected more than " + policy.maxRedirects() + " times");
        } catch (WebPageFetchException e) {
            throw new WebPageFetchException(reader.redactText(e.getMessage(), resolvedValues), e);
        }
    }

    // ------------------------------------------------------------------

    /**
     * A fully read (capped) response. The network seam's return type - tests build these directly.
     */
    protected record RawResponse(
            int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {

        public RawResponse {
            headers = headers == null ? Map.of() : headers;
            body = body == null ? new byte[0] : body;
        }

        /** First value of {@code name}, case-insensitively. */
        public Optional<String> header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null
                        && !entry.getValue().isEmpty()) {
                    return Optional.ofNullable(entry.getValue().get(0));
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The one network seam, over a {@link com.agenticai.core.security.net.SafeHttpClients} client
     * (pinned DNS, no proxy, redirects off). Protected so tests can subclass it with a canned
     * response.
     */
    protected RawResponse send(
            URI uri, String method, Map<String, String> headers, String body, Duration timeout) {
        return sender.send(uri, method, headers, body, timeout);
    }

    /**
     * Input-only rendering for display (workflow run logs, summaries): vault references stay as
     * literal text, input placeholders are substituted in one pass exactly as {@link #execute}
     * will.
     */
    public static String renderForDisplay(String template, Map<String, String> values) {
        return HttpSecretResolution.render(template, values, null);
    }

    private SsrfGuard guard() {
        SsrfGuard override = guardOverride;
        return override != null ? override : SsrfGuard.defaultGuard();
    }

    /** Test seam: a guard with an operator allow-list and fake DNS (see SsrfGuardTest). */
    void useGuard(SsrfGuard guard) {
        this.guardOverride = guard;
    }

    @jakarta.annotation.PreDestroy
    void close() {
        sender.close();
    }

    // ---------------------------------------------- package-private seams kept for callers/tests

    /** Same scheme, host and effective port - the unit a credential is bound to. */
    static boolean sameOrigin(URI a, URI b) {
        return HttpHostPolicy.sameOrigin(a, b);
    }

    static boolean isUntrustedSubject(String subject) {
        return HttpRequestValidation.isUntrustedSubject(subject);
    }

    static void stripCrossOriginCredentials(
            Map<String, String> headers, Set<String> resolvedSecrets) {
        HttpRedirectStage.stripCrossOriginCredentials(headers, resolvedSecrets);
    }

    static String loggableUrl(URI uri, Set<String> secretValues) {
        return HttpLoggableUrl.of(uri, secretValues);
    }

    static String appendQuery(String url, Map<String, String> query) {
        return HttpRequestValidation.appendQuery(url, query);
    }

    static String formEncodeIfJsonObject(String body) {
        return HttpRequestValidation.formEncodeIfJsonObject(body);
    }

    static String normaliseMethod(String method) {
        return HttpRequestValidation.normaliseMethod(method);
    }

    static int clampTimeout(Integer requested) {
        return HttpRequestValidation.clampTimeout(requested);
    }

    static int clampMaxChars(Integer requested) {
        return HttpRequestValidation.clampMaxChars(requested);
    }

    static String normaliseColonReferences(String text) {
        return HttpSecretResolution.normaliseColonReferences(text);
    }

    // ---------------------------------------------------------------- bounded body reader

    /**
     * Result of {@link #readCapped}: at most {@code cap} bytes, and whether the server had more.
     */
    record CappedBody(byte[] bytes, boolean truncated) {}

    /**
     * Daemon pool that runs the blocking body reads of {@link #readCapped}. A cached pool: a read
     * is idle for at most the request timeout, so the thread count is bounded by concurrent tool
     * calls.
     */
    private static final ExecutorService BODY_READER =
            TenantAwareExecutors.newCachedThreadPool(
                    TenantAwareExecutors.threadFactory("http-request-body-reader", true));

    /**
     * Reads at most {@code cap} bytes under an overall deadline, then closes the stream, which
     * cancels the underlying subscription so the rest of the body is never pulled. This is the
     * bounded reader the safe mode uses: the cap is enforced on bytes consumed (one extra byte is
     * read only to tell "exactly cap" from "more than cap"), and the deadline covers the whole
     * read, because {@code HttpRequest.timeout} stops applying once the response headers have
     * arrived - a host that sends headers and then drips the body would otherwise hold the agent
     * turn for as long as it liked. Nothing here relies on {@code BodyHandlers.limiting} (which
     * does not exist on this JDK target).
     */
    static CappedBody readCapped(InputStream in, int cap, Duration deadline) {
        Future<CappedBody> task = BODY_READER.submit(() -> readCappedNow(in, cap));
        try {
            return task.get(Math.max(1L, deadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            HttpSendStage.closeQuietly(in);
            throw new WebPageFetchException("the server took too long to finish responding");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof WebPageFetchException fetchFailure) {
                throw fetchFailure;
            }
            throw new WebPageFetchException("the server stopped responding mid-download");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            HttpSendStage.closeQuietly(in);
            throw new WebPageFetchException("the request was interrupted");
        }
    }

    private static CappedBody readCappedNow(InputStream in, int cap) {
        int limit = cap == Integer.MAX_VALUE ? cap : cap + 1;
        try (in) {
            byte[] buffer = new byte[8_192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int read;
            while (out.size() < limit
                    && (read = in.read(buffer, 0, Math.min(buffer.length, limit - out.size())))
                            != -1) {
                out.write(buffer, 0, read);
            }
            byte[] all = out.toByteArray();
            boolean truncated = all.length > cap;
            return new CappedBody(truncated ? Arrays.copyOf(all, cap) : all, truncated);
        } catch (IOException e) {
            throw new WebPageFetchException("the server stopped responding mid-download");
        }
    }
}
