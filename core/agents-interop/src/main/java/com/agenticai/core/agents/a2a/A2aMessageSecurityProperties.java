package com.agenticai.core.agents.a2a;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Message-level security for A2A requests: detached request signatures, replay protection and the
 * cross-instance hop limit. Bound from the same {@code app.a2a.*} prefix as {@link A2aProperties}
 * (each class binds only the keys it declares), kept separate so the kernel properties class does
 * not grow for an interop-only concern.
 *
 * <p>Everything defaults to a value that keeps existing peers working: signatures are verified when
 * a request carries one, an unsigned request is still accepted ({@code require-signed-messages} is
 * {@code false} for one release), and the hop limit only bites at a depth no ordinary delegation
 * chain reaches.
 */
@Component
@ConfigurationProperties(prefix = "app.a2a")
public class A2aMessageSecurityProperties {

    /**
     * Largest hop number an inbound request may carry (and the largest an outbound call may send).
     * Hop 1 is the first A2A call of a chain, hop 2 a call made while serving a hop-1 request, and
     * so on; a request above this is refused. {@code 0} or less turns the check off.
     */
    private int maxHops = 4;

    // ---- HMAC shared-secret scheme (A2aMessageSecurityFilter / A2aHmacSigner) ----------------
    // An earlier, parallel message-protection scheme to the Ed25519 server/client blocks below:
    // operator-pinned per-peer secrets over X-A2A-* headers. Both coexist; each peer gets the
    // scheme it speaks.

    /** Accept a request with no X-A2A-* headers anyway unless this is on. */
    private boolean requireReplayProtection = false;

    /** Accepted age of an inbound X-A2A-Timestamp. */
    private Duration replayWindow = Duration.ofSeconds(300);

    /** Most nonces remembered per tenant for the X-A2A-Nonce cache. */
    private int nonceCacheMaxPerTenant = 10_000;

    /** Fallback HMAC secret for peers not listed in {@link #signedPeers}; empty = unsigned. */
    private String signingSecret = "";

    /** Refuse requests whose X-A2A-Signature does not verify. */
    private boolean requireSignature = false;

    /** Per-peer HMAC secrets, keyed by host. */
    private List<SignedPeer> signedPeers = new ArrayList<>();

    public static class SignedPeer {

        private String host = "";
        private String secret = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret;
        }
    }

    /** The HMAC secret pinned for {@code host}, falling back to {@link #signingSecret}. */
    public String secretForPeer(String host) {
        String h = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        for (SignedPeer p : signedPeers) {
            if (h.equals(p.getHost())) {
                return p.getSecret().isBlank() ? null : p.getSecret();
            }
        }
        return signingSecret.isBlank() ? null : signingSecret;
    }

    private final Server server = new Server();
    private final Client client = new Client();

    public int getMaxHops() {
        return maxHops;
    }

    public void setMaxHops(int maxHops) {
        this.maxHops = maxHops;
    }

    public Server getServer() {
        return server;
    }

    public Client getClient() {
        return client;
    }

    /** {@code app.a2a.server.*}: how the inbound endpoints treat a signed request. */
    public static class Server {

        /**
         * Verify the message signature when a request carries one (and enforce the timestamp window
         * and the nonce replay cache). A request that carries no signature is unaffected unless
         * {@link #requireSignedMessages} is on.
         */
        private boolean verifySignatures = true;

        /**
         * Refuse requests that carry no valid message signature. Default {@code false} for one
         * release so peers that predate message signing keep working; turn it on once every peer
         * advertises the {@code message-integrity} extension. Implies {@link #verifySignatures}.
         */
        private boolean requireSignedMessages = false;

        /** A signed request is refused when its timestamp is further than this from our clock. */
        private Duration timestampWindow = Duration.ofMinutes(5);

        /**
         * Most nonces remembered per tenant. When a tenant's cache is full of nonces that have not
         * yet expired, new signed requests from it are refused until some expire (replay protection
         * is never weakened by evicting a live nonce).
         */
        private int maxNoncesPerTenant = 10_000;

        /**
         * Optional allow-list of hosts whose agent card may be fetched to find a caller's signing
         * key (exact {@code agents.example.com} or {@code *.example.com}). Empty = any host that
         * passes the SSRF guard, for an already authenticated caller.
         */
        private List<String> trustedCardHosts = new ArrayList<>();

        /**
         * How long a caller's resolved signing key is cached (a failed lookup is cached for 1 min).
         */
        private Duration keyCacheTtl = Duration.ofMinutes(10);

        public boolean isVerifySignatures() {
            return verifySignatures || requireSignedMessages;
        }

        public void setVerifySignatures(boolean verifySignatures) {
            this.verifySignatures = verifySignatures;
        }

        public boolean isRequireSignedMessages() {
            return requireSignedMessages;
        }

        public void setRequireSignedMessages(boolean requireSignedMessages) {
            this.requireSignedMessages = requireSignedMessages;
        }

        public Duration getTimestampWindow() {
            return timestampWindow;
        }

        public void setTimestampWindow(Duration timestampWindow) {
            this.timestampWindow = timestampWindow;
        }

        public int getMaxNoncesPerTenant() {
            return maxNoncesPerTenant;
        }

        public void setMaxNoncesPerTenant(int maxNoncesPerTenant) {
            this.maxNoncesPerTenant = maxNoncesPerTenant;
        }

        public List<String> getTrustedCardHosts() {
            return trustedCardHosts;
        }

        public void setTrustedCardHosts(List<String> trustedCardHosts) {
            this.trustedCardHosts = trustedCardHosts == null ? new ArrayList<>() : trustedCardHosts;
        }

        public Duration getKeyCacheTtl() {
            return keyCacheTtl;
        }

        public void setKeyCacheTtl(Duration keyCacheTtl) {
            this.keyCacheTtl = keyCacheTtl;
        }
    }

    /** {@code app.a2a.client.*}: what the outbound client adds to a call. */
    public static class Client {

        /**
         * Sign outbound requests and send the hop and trace headers, but only to a peer whose Agent
         * Card advertises the {@code message-integrity} extension (a legacy peer gets the request
         * it always got). Signing also needs {@code app.a2a.public-base-url} (the signature names
         * the card the peer fetches our key from) and signing enabled.
         */
        private boolean signRequests = true;

        public boolean isSignRequests() {
            return signRequests;
        }

        public void setSignRequests(boolean signRequests) {
            this.signRequests = signRequests;
        }
    }

    public boolean isRequireReplayProtection() {
        return requireReplayProtection;
    }

    public void setRequireReplayProtection(boolean v) {
        this.requireReplayProtection = v;
    }

    public Duration getReplayWindow() {
        return replayWindow;
    }

    public void setReplayWindow(Duration replayWindow) {
        this.replayWindow = replayWindow == null ? Duration.ofSeconds(300) : replayWindow;
    }

    public int getNonceCacheMaxPerTenant() {
        return nonceCacheMaxPerTenant;
    }

    public void setNonceCacheMaxPerTenant(int nonceCacheMaxPerTenant) {
        this.nonceCacheMaxPerTenant = nonceCacheMaxPerTenant;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    public boolean isRequireSignature() {
        return requireSignature;
    }

    public void setRequireSignature(boolean requireSignature) {
        this.requireSignature = requireSignature;
    }

    public List<SignedPeer> getSignedPeers() {
        return signedPeers;
    }

    public void setSignedPeers(List<SignedPeer> signedPeers) {
        this.signedPeers = signedPeers == null ? new ArrayList<>() : signedPeers;
    }
}
