package com.agenticai.core.agents.a2a;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound half of the optional {@code X-A2A-*} layer (see {@link A2aMessageSecurityFilter} for the
 * inbound half): a fresh timestamp, a single-use nonce and the hop on every outbound request, and
 * for a peer listed in {@code app.a2a.signed-peers} a detached HMAC-SHA256 over them and the body.
 * It serves peers that have no signed Agent Card; a peer that ignores the headers is unaffected.
 * Kept out of {@link A2aClientService} for the file-size cap.
 */
final class A2aReplayHeaders {

    private A2aReplayHeaders() {}

    /**
     * @param outboundHop the hop {@link A2aHopRegistry} numbered for this call (1 = this call
     *     starts a chain); sent 0-based, so the originator sends 0 and a forwarder the inbound
     *     header value plus one
     */
    static void add(
            HttpRequest.Builder request,
            URI target,
            String body,
            int outboundHop,
            A2aReplayProtectionProperties settings) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        int hop = Math.max(0, outboundHop - 1);
        request.header(A2aMessageSecurityFilter.TIMESTAMP_HEADER, timestamp)
                .header(A2aMessageSecurityFilter.NONCE_HEADER, nonce)
                .header(A2aMessageSecurityFilter.HOP_HEADER, Integer.toString(hop));
        String secret = settings.secretForPeer(target.getHost());
        if (secret != null) {
            String path = target.getRawPath() == null ? "/" : target.getRawPath();
            request.header(
                    A2aMessageSecurityFilter.SIGNATURE_HEADER,
                    A2aHmacSigner.sign(
                            secret,
                            "POST",
                            path,
                            timestamp,
                            nonce,
                            hop,
                            body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
