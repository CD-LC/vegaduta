package com.agenticai.core.agents.a2a;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Message-level protection for A2A requests (OWASP ASI07): replay window, hop limit and the
 * optional detached HMAC signature. Bound to the same {@code app.a2a} prefix as {@code
 * A2aProperties} (which lives in the kernel module); unknown keys are ignored on both sides.
 *
 * <p>Every default keeps third-party A2A peers working unchanged: nothing is required of an inbound
 * request unless {@link #requireReplayProtection} or {@link #requireSignature} is switched on.
 */
@ConfigurationProperties(prefix = "app.a2a")
public class A2aReplayProtectionProperties {

    /** Inbound requests whose {@code X-A2A-Hop} exceeds this are refused (cross-instance loops). */
    private int maxHops = 4;

    /**
     * When true, an inbound A2A POST without {@code X-A2A-Timestamp}, {@code X-A2A-Nonce} and
     * {@code X-A2A-Hop} is refused. Default false: third-party peers do not send them.
     */
    private boolean requireReplayProtection = false;

    /** Accepted clock skew either side of now; also the nonce time-to-live. */
    private Duration replayWindow = Duration.ofSeconds(300);

    /** In-memory nonce cap per tenant; past it new nonces are refused (fail closed). */
    private int nonceCacheMaxPerTenant = 10_000;

    /**
     * Shared secret verifying an inbound {@code X-A2A-Signature}. Blank: signatures are not
     * verified (a present header is ignored).
     */
    private String signingSecret = "";

    /** When true (and a secret is set) every inbound A2A POST must carry a valid signature. */
    private boolean requireSignature = false;

    /** Peers (by host) that get an {@code X-A2A-Signature} on outbound calls. Default none. */
    private List<SignedPeer> signedPeers = new ArrayList<>();

    public static class SignedPeer {
        private String host = "";
        private String secret = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /** The secret for {@code host}, or null when the peer is not configured for signing. */
    public String secretForPeer(String host) {
        if (host == null) {
            return null;
        }
        for (SignedPeer p : signedPeers) {
            if (p.host != null
                    && !p.host.isBlank()
                    && p.host.equalsIgnoreCase(host)
                    && p.secret != null
                    && !p.secret.isBlank()) {
                return p.secret;
            }
        }
        return null;
    }

    public int getMaxHops() {
        return maxHops;
    }

    public void setMaxHops(int maxHops) {
        this.maxHops = maxHops;
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
        this.replayWindow = replayWindow;
    }

    public int getNonceCacheMaxPerTenant() {
        return nonceCacheMaxPerTenant;
    }

    public void setNonceCacheMaxPerTenant(int v) {
        this.nonceCacheMaxPerTenant = v;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
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
