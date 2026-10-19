package com.agenticai.core.agents.a2a;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.a2a.*} config block for the inbound Agent2Agent (A2A) server
 * surface ({@link A2aAgentCardController}, {@link A2aJsonRpcController}).
 * Registered via {@link A2aConfiguration}'s {@code @EnableConfigurationProperties}
 * - same per-module pattern as {@code VisionProperties}/{@code AgentJobProperties}
 * (there is no application-wide {@code @ConfigurationPropertiesScan}).
 *
 * <p>Nothing here is required to be set: every default below produces a working
 * server on a fresh deployment. {@code publicBaseUrl} is the one value a real
 * deployment behind a reverse proxy will usually want to pin - see its javadoc.
 */
@ConfigurationProperties(prefix = "app.a2a")
public class A2aProperties {

    /**
     * Platform-wide kill switch. {@code false} removes every A2A route from the
     * context (the controllers are {@code @ConditionalOnProperty}-gated on this),
     * so a disabled server answers 404 rather than a protocol-level error - an
     * external agent probing {@code /.well-known/agent-card.json} then learns
     * nothing about what the platform could serve.
     */
    private boolean enabled = true;

    /**
     * The externally reachable origin of THIS core service, e.g.
     * {@code https://api.staging.example.com} - what gets baked into the Agent Card's
     * {@code url}/{@code supportedInterfaces[].url}. Blank (the default) derives
     * it from the current request (scheme + host + port + context path), which
     * is correct whenever {@code server.forward-headers-strategy=native} sees
     * the proxy's {@code X-Forwarded-*} headers (it does - see application.yml).
     * Set explicitly when the card is fetched over an internal hostname (a
     * sidecar, a health checker, the web app's own server-side proxy) but must
     * still advertise the public one.
     */
    private String publicBaseUrl = "";

    /**
     * The {@code version} field of the Agent Card. Blank falls back to the
     * jar's {@code Implementation-Version} manifest entry, then to a fixed
     * development marker - the card must always carry SOME version string
     * (it is a required field in both 0.3 and 1.0).
     */
    private String version = "";

    /**
     * How long a finished task stays retrievable via {@code GetTask} /
     * {@code tasks/get}. See {@link A2aTaskStore}'s javadoc for why the store is
     * in-memory and what that means for this TTL across restarts/replicas.
     */
    private Duration taskTtl = Duration.ofHours(24);

    /**
     * Upper bound a BLOCKING {@code SendMessage} (and each streamed chunk wait)
     * holds the request thread for. Generous on purpose: CAREFUL/RIGOROUS
     * reasoning-depth agents legitimately take minutes; the bound exists so a
     * wedged upstream can never pin a servlet thread forever.
     */
    private Duration blockingTimeout = Duration.ofMinutes(5);

    /**
     * Hard cap on tasks held in memory (across all tenants). Reached only under
     * abuse or a very busy multi-tenant box; beyond it the store evicts the
     * oldest TERMINAL tasks first, so a GetTask for something that finished a
     * while ago may 404 earlier than {@link #taskTtl} promises.
     */
    private int maxTasks = 10_000;

    /**
     * How many non-blocking ({@code returnImmediately}/{@code blocking:false})
     * turns may run concurrently. Each one is a full agent turn on a virtual
     * thread; this keeps an external caller from fanning out unbounded LLM
     * calls with a single burst of fire-and-forget requests.
     */
    private int maxConcurrentAsyncTasks = 32;

    /**
     * Where {@link A2aTaskStore} keeps task records: {@code memory} (default,
     * per-JVM, today's behaviour) or {@code redis} (a tenant-scoped JSON
     * snapshot mirrored to Redis with {@link #taskTtl}, so {@code GetTask} /
     * {@code CancelTask} work from any core replica). Redis errors always fall
     * back to the in-memory copy - see {@link A2aTaskStore}'s javadoc for what
     * the redis mode does and does not replicate.
     */
    private String taskStore = "memory";

    /**
     * Redis mode only: how often the owning replica refreshes the heartbeat of its
     * non-terminal task records and checks them for a remote cancel. Remote-cancel
     * latency is bounded by this when pub/sub is unavailable.
     */
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    /**
     * Redis mode only: a non-terminal record whose heartbeat is older than this and that
     * belongs to ANOTHER instance id (a previous boot or a dead replica) is marked FAILED
     * with "interrupted by restart". Keep it at least 3x {@link #heartbeatInterval}.
     */
    private Duration heartbeatStaleAfter = Duration.ofSeconds(60);

    /**
     * When true, boot FAILS if signing is enabled but no
     * {@code signing.private-key-pem} is pinned - an ephemeral per-boot key
     * gives every replica a different marketplace identity. Default false
     * (an ephemeral key is only WARNed about, and only when
     * {@code app.replica-mode} hints at a multi-replica shape).
     */
    private boolean requirePinnedKey = false;

    /** Card signing - see {@link A2aCardSigningService}'s javadoc. */
    private Signing signing = new Signing();

    /** Outbound client - see {@link A2aClientService}. Default OFF. */
    private Client client = new Client();

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    /**
     * {@code app.a2a.client.*}: calling EXTERNAL A2A agents as a tool
     * ({@code a2a_call_agent}). Everything defaults to the safest value:
     * disabled, and an empty allow-list means no host is reachable at all.
     */
    public static class Client {

        /** Master switch; when false the tool is never offered and the service refuses. */
        private boolean enabled = false;

        /**
         * Egress allow-list of hosts (exact {@code agents.example.com} or
         * {@code *.example.com} for subdomains only). Applies to the card URL,
         * the card's JSON-RPC endpoint and its JWKS URL. Empty = nothing allowed.
         */
        private List<String> allowedHosts = new ArrayList<>();

        /** Refuse remote cards whose detached JWS does not verify (default true). */
        private boolean requireSignedCard = true;

        /** Permit plain http to a loopback host. Dev/tests only; never for real remotes. */
        private boolean allowInsecureLoopback = false;

        private Duration connectTimeout = Duration.ofSeconds(5);
        /** Per HTTP request (card, JWKS, send, poll). */
        private Duration requestTimeout = Duration.ofSeconds(30);
        /** Total time one tool call may wait for the remote task to finish. */
        private Duration maxWait = Duration.ofSeconds(120);
        private Duration pollInterval = Duration.ofSeconds(2);
        /** Hard cap on any single remote response body. */
        private int maxResponseBytes = 1_048_576;
        /** Cap on the text handed back to the model. */
        private int maxResultChars = 20_000;

        /**
         * Most {@code a2a_call_agent} calls one agent turn may make (OWASP ASI07/ASI08: a
         * looping or injected agent must not fan out to remote agents without bound). Each
         * resolved tool set counts on its own, so this is per turn. 0 or less = no cap.
         */
        private int maxCallsPerTurn = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(List<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
        }

        public boolean isRequireSignedCard() {
            return requireSignedCard;
        }

        public void setRequireSignedCard(boolean requireSignedCard) {
            this.requireSignedCard = requireSignedCard;
        }

        public boolean isAllowInsecureLoopback() {
            return allowInsecureLoopback;
        }

        public void setAllowInsecureLoopback(boolean allowInsecureLoopback) {
            this.allowInsecureLoopback = allowInsecureLoopback;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getMaxCallsPerTurn() {
            return maxCallsPerTurn;
        }

        public void setMaxCallsPerTurn(int maxCallsPerTurn) {
            this.maxCallsPerTurn = maxCallsPerTurn;
        }

        public int getMaxResultChars() {
            return maxResultChars;
        }

        public void setMaxResultChars(int maxResultChars) {
            this.maxResultChars = maxResultChars;
        }
    }

    public static class Signing {

        /**
         * Master switch for the {@code signatures} field on emitted cards and
         * the {@code /.well-known/jwks.json} endpoint. Default ON: an
         * ephemeral boot key still demonstrates the wire format end-to-end;
         * pin real key material for a stable identity.
         */
        private boolean enabled = true;

        /**
         * PKCS8 PEM (or raw 32-byte seed, base64/hex) of the Ed25519 signing
         * key - the deployment's marketplace identity. When set,
         * {@link #publicKeyPem} is REQUIRED too (the JDK cannot derive an
         * Ed25519 public key from a PKCS8 private). Empty = ephemeral per boot.
         */
        private String privateKeyPem = "";

        /** X.509 PEM of the public half of {@link #privateKeyPem}. */
        private String publicKeyPem = "";

        /** Optional stable {@code kid}; default is a SHA-256 thumbprint of the key. */
        private String keyId = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public void setPrivateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
        }

        public String getPublicKeyPem() {
            return publicKeyPem;
        }

        public void setPublicKeyPem(String publicKeyPem) {
            this.publicKeyPem = publicKeyPem;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }
    }

    public String getTaskStore() {
        return taskStore;
    }

    public void setTaskStore(String taskStore) {
        this.taskStore = taskStore;
    }

    public boolean isRequirePinnedKey() {
        return requirePinnedKey;
    }

    public void setRequirePinnedKey(boolean requirePinnedKey) {
        this.requirePinnedKey = requirePinnedKey;
    }

    public Signing getSigning() {
        return signing;
    }

    public void setSigning(Signing signing) {
        this.signing = signing;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Duration getTaskTtl() {
        return taskTtl;
    }

    public void setTaskTtl(Duration taskTtl) {
        this.taskTtl = taskTtl;
    }

    public Duration getBlockingTimeout() {
        return blockingTimeout;
    }

    public void setBlockingTimeout(Duration blockingTimeout) {
        this.blockingTimeout = blockingTimeout;
    }

    public int getMaxTasks() {
        return maxTasks;
    }

    public void setMaxTasks(int maxTasks) {
        this.maxTasks = maxTasks;
    }

    public int getMaxConcurrentAsyncTasks() {
        return maxConcurrentAsyncTasks;
    }

    public void setMaxConcurrentAsyncTasks(int maxConcurrentAsyncTasks) {
        this.maxConcurrentAsyncTasks = maxConcurrentAsyncTasks;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getHeartbeatStaleAfter() {
        return heartbeatStaleAfter;
    }

    public void setHeartbeatStaleAfter(Duration heartbeatStaleAfter) {
        this.heartbeatStaleAfter = heartbeatStaleAfter;
    }
}
