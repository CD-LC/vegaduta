package com.agenticai.core.sdlc;

import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxArtifact;
import com.agenticai.core.sdlc.dto.SandboxFile;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Master Roadmap 2026 item #14 (E2B code execution completion, §B).
 *
 * <p><b>Why this exists instead of extending {@link E2bSandboxClient} to talk to envd directly:</b>
 * E2B's own {@code Process}/{@code Filesystem} services (what a "real" {@code runCode} would
 * normally call) are served by <b>envd</b> over Connect-RPC/protobuf, not a documented plain-JSON
 * REST contract - see {@link E2bSandboxClient}'s own javadoc for the research trail (confirmed via
 * E2B's public Go SDK bindings: {@code StartRequest}/ {@code ProcessConfig}/{@code ProcessEvent}
 * are real, but the wire format for calling them without E2B's official Python/JS SDK - there is no
 * official Java/Kotlin SDK as of this pass - would have to be reverse engineered rather than read
 * off a spec). Guessing at that wire format would either silently fail or silently succeed against
 * the wrong contract - not acceptable for a capability whose whole job is running arbitrary code.
 *
 * <p>Instead, this class talks to a <b>tenant-owned E2B template</b> (see {@code
 * core/sdlc/e2b-template/}) that runs a small stdlib-only HTTP server ({@code runner.py}) exposing
 * exactly one contract we define and control: {@code POST /run {"code","language","timeoutSeconds"}
 * -> {"stdout", "stderr","exitCode","timedOut"}}. Reaching it uses only confirmed-simple, publicly
 * documented REST: E2B's own JS/Python SDK docs and live examples confirm every sandbox port is
 * reachable at {@code https://{port}-{ sandboxId}.e2b.app} (E2B calls this {@code getHost(port)}) -
 * this class hits that URL directly with a plain {@link RestClient}, no protobuf, no guessed
 * headers.
 *
 * <p>Sandbox lifecycle (create/kill) is delegated to the already-implemented, already-confirmed
 * {@link E2bSandboxClient} rather than duplicated here.
 */
@Component
public class E2bCodeExecutionClient {

    private static final Logger log = LoggerFactory.getLogger(E2bCodeExecutionClient.class);

    /**
     * Port {@code runner.py} listens on inside the template - see
     * core/sdlc/e2b-template/Dockerfile.
     */
    private static final int RUNNER_PORT = 8080;

    private static final String SANDBOX_DOMAIN_SUFFIX = ".e2b.app";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * How long to keep retrying the first request while runner.py finishes booting inside the
     * freshly created sandbox.
     */
    private static final Duration BOOT_GRACE_PERIOD = Duration.ofSeconds(8);

    private final RestClient restClient;
    private final E2bSandboxClient sandboxClient;

    public E2bCodeExecutionClient(
            RestClient.Builder restClientBuilder, E2bSandboxClient sandboxClient) {
        this.restClient = restClientBuilder.build();
        this.sandboxClient = sandboxClient;
    }

    /**
     * Creates a fresh sandbox from {@code templateId}, waits for {@code runner.py} to come up,
     * POSTs the run request, then always kills the sandbox afterward (success or failure) - each
     * call gets an isolated, disposable environment rather than a reused long-lived one, matching
     * the "sandbox" framing E2B itself uses.
     */
    public RunCodeResponse runCode(
            String templateId, String code, String language, Integer timeoutSeconds) {
        return runCode(templateId, code, language, timeoutSeconds, null);
    }

    /**
     * Same as the 4-arg form, plus data-analysis sandbox support: {@code files} are mounted in the
     * run's working directory and any output files the runner reports come back as {@link
     * RunCodeResponse#artifacts()}. A template built before this feature ignores {@code files} and
     * returns no {@code artifacts} - the call still succeeds, just without them.
     */
    public RunCodeResponse runCode(
            String templateId,
            String code,
            String language,
            Integer timeoutSeconds,
            List<SandboxFile> files) {
        return execute(null, templateId, code, language, timeoutSeconds, files);
    }

    /**
     * {@code run_code} form: runs on the given {@link E2bAccess} (the tenant's own key or the
     * platform-managed fallback, decided by {@link E2bAccessResolver}) instead of looking the
     * tenant credential up again. Create and kill use the same key.
     */
    public RunCodeResponse runCode(
            E2bAccess access,
            String code,
            String language,
            Integer timeoutSeconds,
            List<SandboxFile> files) {
        if (access == null) {
            throw new E2bNotConfiguredException();
        }
        return execute(access.apiKey(), access.templateId(), code, language, timeoutSeconds, files);
    }

    /**
     * {@code apiKey} null = the tenant's stored credential (resolved by {@link E2bSandboxClient}).
     */
    private RunCodeResponse execute(
            String apiKey,
            String templateId,
            String code,
            String language,
            Integer timeoutSeconds,
            List<SandboxFile> files) {
        int effectiveTimeout =
                (timeoutSeconds == null || timeoutSeconds <= 0)
                        ? DEFAULT_TIMEOUT_SECONDS
                        : timeoutSeconds;
        // A few extra seconds of sandbox TTL beyond the run timeout so the kill call below always
        // has a live sandbox to hit, even if the run itself takes the full budget.
        int sandboxTtlSeconds = effectiveTimeout + 20;

        Map<String, String> metadata = Map.of("purpose", "code-execution");
        E2bSandboxClient.SandboxHandle handle;
        try {
            handle =
                    apiKey == null
                            ? sandboxClient.createSandbox(
                                    templateId, sandboxTtlSeconds, null, metadata)
                            : sandboxClient.createSandbox(
                                    apiKey, templateId, sandboxTtlSeconds, null, metadata);
        } catch (E2bApiException e) {
            // Distinct type: nothing was created, so a metered caller may give the run back.
            throw new E2bSandboxCreateException(e);
        }
        String host = RUNNER_PORT + "-" + handle.sandboxId() + SANDBOX_DOMAIN_SUFFIX;
        String runUrl = "https://" + host + "/run";

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            body.put("language", language);
            body.put("timeoutSeconds", effectiveTimeout);
            if (files != null && !files.isEmpty()) {
                body.put("files", encodeFiles(files));
            }

            JsonNode response = postWithBootRetry(runUrl, handle.trafficAccessToken(), body);

            return new RunCodeResponse(
                    response.path("stdout").asText(""),
                    response.path("stderr").asText(""),
                    response.path("exitCode").isNull() ? null : response.path("exitCode").asInt(),
                    response.path("timedOut").asBoolean(false),
                    handle.sandboxId(),
                    decodeArtifacts(response.path("artifacts")));
        } finally {
            try {
                if (apiKey == null) {
                    sandboxClient.killSandbox(handle.sandboxId());
                } else {
                    sandboxClient.killSandbox(apiKey, handle.sandboxId());
                }
            } catch (Exception e) {
                // Best-effort cleanup - the sandbox's own TTL (sandboxTtlSeconds above) reaps it
                // regardless, so a failed kill here is a minor cost overrun, not a correctness bug.
                log.warn(
                        "Could not kill code-execution sandbox {} after use: {}",
                        handle.sandboxId(),
                        e.getMessage());
            }
        }
    }

    /**
     * A sandbox that stays up across several {@code /run} calls (the coding harness keeps its git
     * workspace on the sandbox's disk between commands). Unlike {@link #runCode} - one disposable
     * sandbox per call - the caller owns the lifetime: {@link Session#close()} MUST be called, and
     * the sandbox TTL ({@code ttlSeconds}) is the backstop that reaps it if the JVM dies first.
     */
    public Session openSession(E2bAccess access, int ttlSeconds) {
        if (access == null) {
            throw new E2bNotConfiguredException();
        }
        Map<String, String> metadata = Map.of("purpose", "coding-harness");
        E2bSandboxClient.SandboxHandle handle;
        try {
            handle =
                    sandboxClient.createSandbox(
                            access.apiKey(), access.templateId(), ttlSeconds, null, metadata);
        } catch (E2bApiException e) {
            throw new E2bSandboxCreateException(e);
        }
        return new Session(access.apiKey(), handle.sandboxId(), handle.trafficAccessToken());
    }

    /**
     * One live sandbox; see {@link #openSession}. Not thread-safe by design: the harness runs one
     * command at a time.
     */
    public final class Session implements AutoCloseable {
        private final String apiKey;
        private final String sandboxId;

        /**
         * Gates the sandbox host when public traffic is restricted; null = open sandbox (rollback
         * flag).
         */
        private final String trafficAccessToken;

        private final String runUrl;
        private boolean closed;

        private Session(String apiKey, String sandboxId, String trafficAccessToken) {
            this.apiKey = apiKey;
            this.sandboxId = sandboxId;
            this.trafficAccessToken = trafficAccessToken;
            this.runUrl =
                    "https://" + RUNNER_PORT + "-" + sandboxId + SANDBOX_DOMAIN_SUFFIX + "/run";
        }

        public String sandboxId() {
            return sandboxId;
        }

        /** Runs {@code code} in {@code language} (python|node|bash) inside this sandbox. */
        public RunCodeResponse run(
                String code, String language, int timeoutSeconds, List<SandboxFile> files) {
            if (closed) {
                throw new IllegalStateException("The sandbox session is closed.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            body.put("language", language);
            body.put("timeoutSeconds", Math.max(1, timeoutSeconds));
            if (files != null && !files.isEmpty()) {
                body.put("files", encodeFiles(files));
            }
            JsonNode response = postWithBootRetry(runUrl, trafficAccessToken, body);
            return new RunCodeResponse(
                    response.path("stdout").asText(""),
                    response.path("stderr").asText(""),
                    response.path("exitCode").isNull() ? null : response.path("exitCode").asInt(),
                    response.path("timedOut").asBoolean(false),
                    sandboxId,
                    List.of());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                sandboxClient.killSandbox(apiKey, sandboxId);
            } catch (Exception e) {
                // Best effort: the sandbox TTL reaps it regardless.
                log.warn("Could not kill coding sandbox {}: {}", sandboxId, e.getMessage());
            }
        }
    }

    private static List<Map<String, String>> encodeFiles(List<SandboxFile> files) {
        List<Map<String, String>> out = new ArrayList<>();
        for (SandboxFile file : files) {
            if (file == null || file.name() == null || file.content() == null) {
                continue;
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", file.name());
            entry.put("contentBase64", Base64.getEncoder().encodeToString(file.content()));
            out.add(entry);
        }
        return out;
    }

    // Same caps core/sdlc/e2b-template/runner.py applies (MAX_ARTIFACTS / MAX_ARTIFACT_BYTES /
    // MAX_ARTIFACT_TOTAL_BYTES). Enforced here too because the response comes from a tenant-owned
    // template we do not control: an old, modified or hostile runner must not be able to push
    // unbounded bytes into core's heap and object storage.
    static final int MAX_ARTIFACTS = 8;
    static final int MAX_ARTIFACT_BYTES = 5 * 1024 * 1024;
    static final int MAX_ARTIFACT_TOTAL_BYTES = 12 * 1024 * 1024;

    static List<SandboxArtifact> decodeArtifacts(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<SandboxArtifact> out = new ArrayList<>();
        long total = 0;
        for (JsonNode item : node) {
            if (out.size() >= MAX_ARTIFACTS) {
                log.warn("Dropping sandbox artifacts beyond the {} per-run cap", MAX_ARTIFACTS);
                break;
            }
            String name = item.path("name").asText("");
            String mime = item.path("mimeType").asText("application/octet-stream");
            String b64 = item.path("contentBase64").asText("");
            if (name.isBlank() || b64.isEmpty()) {
                continue;
            }
            // Check the encoded length BEFORE decoding so an oversized payload is never allocated.
            long approxDecoded = (long) b64.length() / 4 * 3;
            if (approxDecoded - 2 > MAX_ARTIFACT_BYTES) {
                log.warn(
                        "Skipping sandbox artifact '{}': over the {} byte per-file cap",
                        name,
                        MAX_ARTIFACT_BYTES);
                continue;
            }
            if (total + approxDecoded - 2 > MAX_ARTIFACT_TOTAL_BYTES) {
                log.warn(
                        "Skipping sandbox artifact '{}': per-run total cap of {} bytes reached",
                        name,
                        MAX_ARTIFACT_TOTAL_BYTES);
                continue;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(b64);
                if (bytes.length > MAX_ARTIFACT_BYTES
                        || total + bytes.length > MAX_ARTIFACT_TOTAL_BYTES) {
                    log.warn("Skipping sandbox artifact '{}': over the size caps", name);
                    continue;
                }
                total += bytes.length;
                out.add(new SandboxArtifact(name, mime, bytes));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping sandbox artifact '{}' with invalid base64", name);
            }
        }
        return out;
    }

    /**
     * runner.py's HTTP server takes a moment to start after the sandbox itself reports as created
     * (E2B's own {@code createSandbox} response only confirms the VM booted, not that our process
     * inside it is listening yet) - retries connection-refused-shaped failures for up to {@link
     * #BOOT_GRACE_PERIOD} before giving up, rather than either failing the very first call every
     * time or silently sleeping a fixed guess.
     *
     * <p>{@code trafficAccessToken} (from the create call, see {@link
     * E2bSandboxClient.SandboxHandle#trafficAccessToken()}) goes out as the {@code
     * e2b-traffic-access-token} header on EVERY request to the sandbox host, retries included: with
     * public traffic restricted the sandbox proxy answers 403 without it
     * (https://docs.e2b.dev/network/restrict-public-access). Null (rollback flag off) sends no
     * header.
     */
    private JsonNode postWithBootRetry(String url, String trafficAccessToken, Object body) {
        long deadline = System.currentTimeMillis() + BOOT_GRACE_PERIOD.toMillis();
        RestClientException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                RestClient.RequestBodySpec request = restClient.post().uri(url);
                if (trafficAccessToken != null) {
                    request =
                            request.header(
                                    E2bSandboxClient.TRAFFIC_ACCESS_TOKEN_HEADER,
                                    trafficAccessToken);
                }
                return request.body(body).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException e) {
                // The runner answered but with an error status - that's a real failure, not a
                // boot-in-progress signal, so surface it immediately rather than retrying.
                throw new E2bApiException(
                        e.getStatusCode().value(),
                        "Code-execution template returned an error: " + describeError(e));
            } catch (RestClientException e) {
                lastError = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new E2bApiException(
                0,
                "Could not reach the code-execution template's /run endpoint at "
                        + url
                        + " within "
                        + BOOT_GRACE_PERIOD.getSeconds()
                        + "s - is the template built from core/sdlc/e2b-template/ and listening on port "
                        + RUNNER_PORT
                        + "? "
                        + (lastError != null ? lastError.getMessage() : ""));
    }

    private static String describeError(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String detail = (body == null || body.isBlank()) ? null : body.trim();
        return "HTTP " + e.getStatusCode().value() + (detail != null ? ": " + detail : "");
    }
}
