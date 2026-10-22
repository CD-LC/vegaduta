package com.agenticai.core.agents.a2a;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The inbound half of signed Agent Cards - what the cross-tenant marketplace
 * consumes. {@link A2aCardSigningService} signs OUR cards; this verifies
 * THEIRS: fetch a card from a remote deployment, resolve the publisher's JWKS
 * (the card's {@code jwks_uri} if advertised, else
 * {@code {origin}/.well-known/jwks.json} - the path this platform itself
 * serves), match the signature's {@code kid}, and re-verify the detached JWS.
 *
 * <p><b>Trust boundary.</b> Verification proves the card was signed by whoever
 * holds the key the SAME ORIGIN's JWKS publishes - that is origin-bound proof
 * of integrity, not independent attestation (a malicious origin can sign its
 * own lies). A marketplace layer that wants more must pin trusted kids per
 * publisher on top of this. Documented rather than hidden: {@link
 * VerificationResult#verified()} true means "signed and unmodified, by a key
 * this origin claims", full stop.
 *
 * <p><b>URL policy.</b> HTTPS required for remote cards; plain HTTP is
 * accepted only for loopback (dev/self-test). The URL is operator-supplied
 * config, not model/tool input - same posture as {@code
 * HttpWebTaskBackend}'s provider URL. Timeouts are short (10s) because card
 * verification is a synchronous part of a marketplace listing check.
 *
 * <p><b>SSRF (security review SEC-048).</b> The production fetcher is built
 * on {@code SafeHttpClients}: every URL (card and JWKS) is validated by the
 * shared {@link SsrfGuard}, the socket goes only to an address validated in
 * the same DNS lookup, no proxy is used and redirects are NOT followed (a 3xx
 * is a fetch failure). The card's {@code jwks_uri} is attacker-chosen content,
 * so it must be same-origin with the card URL or an https URL that passes
 * the guard's host rules; the fetcher then applies the full guard to it. The
 * loopback dev path therefore only works when the operator lists the host in
 * {@code app.security.outbound.allowed-private-hosts}.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Service
public class A2aCardVerifier {

    private static final Logger log = LoggerFactory.getLogger(A2aCardVerifier.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * {@code verified} = at least one signature entry verified against a JWKS
     * key; {@code kid} = the key that verified (null when none did);
     * {@code detail} = human-readable failure/skip reason for logs and admin
     * surfaces - never an exception message blob.
     */
    public record VerificationResult(boolean verified, String kid, String detail) {}

    /** Injectable so tests stub the network; production uses the HttpClient default. */
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    private final A2aCardSigningService signingService;
    private final Fetcher fetcher;
    private final SsrfGuard guard;

    // @Autowired is REQUIRED, not decorative: the package-private test-seam
    // constructor below means this class has two constructors, and without
    // the marker Spring falls back to a no-arg <init> that does not exist -
    // the bean then fails instantiation at boot (observed on the first prod
    // boot of this build, 2026-09-23).
    @Autowired
    public A2aCardVerifier(A2aCardSigningService signingService, SsrfGuard guard) {
        this(signingService, guardedFetcher(guard), guard);
    }

    A2aCardVerifier(A2aCardSigningService signingService, Fetcher fetcher) {
        this(signingService, fetcher, SsrfGuard.defaultGuard());
    }

    A2aCardVerifier(A2aCardSigningService signingService, Fetcher fetcher, SsrfGuard guard) {
        this.signingService = signingService;
        this.fetcher = fetcher;
        this.guard = guard;
    }

    /**
     * SEC-048: the production fetcher. Guard-validated, address-pinned,
     * proxy-free, redirects off, body capped. Package-private for tests.
     */
    static Fetcher guardedFetcher(SsrfGuard guard) {
        CloseableHttpClient http = SafeHttpClients.create(guard, TIMEOUT, TIMEOUT, false);
        return url -> {
            URI uri;
            try {
                uri = guard.validate(url).uri();
            } catch (SsrfBlockedException e) {
                throw new IOException("refused: " + e.getMessage());
            }
            HttpGet get = new HttpGet(uri);
            get.addHeader("Accept", "application/json");
            ClassicHttpResponse response = http.executeOpen(null, get, null);
            try {
                int status = response.getCode();
                if (status >= 300) {
                    throw new IOException("HTTP " + status + (status < 400 ? " (redirects are not followed)" : ""));
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    return "";
                }
                InputStream in = entity.getContent();
                byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    throw new IOException("response larger than " + MAX_BODY_BYTES + " bytes");
                }
                return new String(body, StandardCharsets.UTF_8);
            } finally {
                // Cancel, then close: never drain an oversized or endless body.
                get.cancel();
                response.close();
            }
        };
    }

    private static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * SEC-048: a card-supplied {@code jwks_uri} must be same-origin with the
     * card, or an https URL that passes the guard's (DNS-free) host rules.
     * The fetcher then runs the full, resolving guard on it. Null = allowed.
     */
    String jwksUriRefusal(URI cardUri, String jwksUrl) {
        URI jwks;
        try {
            jwks = new URI(jwksUrl.strip());
        } catch (Exception e) {
            return "jwks_uri is not a valid URL";
        }
        if (jwks.getHost() != null && SsrfGuard.sameOrigin(cardUri, jwks)) {
            return null;
        }
        if (!"https".equalsIgnoreCase(jwks.getScheme())) {
            return "jwks_uri on another origin must be https";
        }
        try {
            guard.validateWithoutDns(jwksUrl);
            return null;
        } catch (SsrfBlockedException e) {
            return "jwks_uri refused: " + e.getMessage();
        }
    }

    /**
     * Verifies a remote card end-to-end. Never throws - every failure (fetch,
     * parse, missing JWKS, bad signature) returns {@code verified=false} with a
     * {@code detail} an operator can act on.
     */
    @SuppressWarnings("unchecked")
    public VerificationResult verifyRemote(String cardUrl) {
        URI uri;
        try {
            uri = URI.create(cardUrl == null ? "" : cardUrl.trim());
        } catch (IllegalArgumentException e) {
            return new VerificationResult(false, null, "card URL is not parseable");
        }
        String scheme = uri.getScheme();
        boolean loopback = uri.getHost() != null
            && (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1"));
        if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && loopback)) {
            return new VerificationResult(false, null, "card URL must be https (http allowed only for loopback)");
        }

        Map<String, Object> card;
        try {
            card = MAPPER.readValue(fetcher.get(cardUrl), Map.class);
        } catch (Exception e) {
            return new VerificationResult(false, null, "could not fetch/parse card: " + e.getMessage());
        }
        return verifyCard(card, uri, fetcher);
    }

    /**
     * Verifies an ALREADY-FETCHED card (the outbound A2A client fetches the card
     * itself through its SSRF guard, then must verify exactly the bytes it will
     * act on - re-fetching would open a swap window). {@code jwksFetcher}
     * retrieves the JWKS, which may live at a card-advertised {@code jwks_uri}
     * the caller must therefore be able to police.
     */
    @SuppressWarnings("unchecked")
    VerificationResult verifyCard(Map<String, Object> card, URI uri, Fetcher jwksFetcher) {
        Object signatures = card.get("signatures");
        if (!(signatures instanceof List<?> list) || list.isEmpty()) {
            return new VerificationResult(false, null, "card carries no signatures");
        }
        String kid = null;
        for (Object entryObj : list) {
            if (entryObj instanceof Map<?, ?> entry) {
                Object header = entry.get("header");
                if (header instanceof Map<?, ?> h && h.get("kid") instanceof String k) {
                    kid = k;
                    break;
                }
            }
        }

        String jwksUrl;
        Object advertised = card.get("jwks_uri");
        if (advertised instanceof String s && StringUtils.hasText(s)) {
            jwksUrl = s;
            String refusal = jwksUriRefusal(uri, s);
            if (refusal != null) {
                return new VerificationResult(false, kid, refusal);
            }
        } else {
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            jwksUrl = uri.getScheme() + "://" + uri.getHost() + port + "/.well-known/jwks.json";
        }
        Map<String, Object> jwks;
        try {
            jwks = MAPPER.readValue(jwksFetcher.get(jwksUrl), Map.class);
        } catch (Exception e) {
            return new VerificationResult(false, kid, "could not fetch/parse JWKS at " + jwksUrl + ": " + e.getMessage());
        }
        Object keys = jwks.get("keys");
        if (!(keys instanceof List<?> keyList)) {
            return new VerificationResult(false, kid, "JWKS at " + jwksUrl + " has no keys");
        }
        for (Object keyObj : keyList) {
            if (!(keyObj instanceof Map<?, ?> jwk)) {
                continue;
            }
            if (kid != null && !kid.equals(jwk.get("kid"))) {
                continue;
            }
            if (!"OKP".equals(jwk.get("kty")) || !"Ed25519".equals(jwk.get("crv"))
                || !(jwk.get("x") instanceof String x)) {
                continue;
            }
            try {
                PublicKey publicKey = okpToPublicKey(x);
                if (signingService.verify(card, publicKey)) {
                    return new VerificationResult(true, (String) jwk.get("kid"), "verified against " + jwksUrl);
                }
            } catch (GeneralSecurityException e) {
                log.debug("Skipping malformed Ed25519 JWK (kid={})", jwk.get("kid"), e);
            }
        }
        return new VerificationResult(false, kid, "no JWKS key verified the card's signatures");
    }

    /** RFC 8037 OKP {@code x} (base64url, little-endian 32 bytes) to a JDK Ed25519 PublicKey. */
    static PublicKey okpToPublicKey(String xBase64Url) throws GeneralSecurityException {
        byte[] x = Base64.getUrlDecoder().decode(xBase64Url);
        if (x.length != 32) {
            throw new GeneralSecurityException("Ed25519 OKP x must be 32 bytes, got " + x.length);
        }
        // RFC 8032 encoding: little-endian y with the x-coordinate's parity in
        // the MSB of the last byte. EdECPoint wants that parity as xOdd and y
        // big-endian - so pull the bit, mask it out, then reverse.
        boolean xOdd = (x[31] & 0x80) != 0;
        x[31] &= 0x7f;
        byte[] reversed = new byte[32];
        for (int i = 0; i < 32; i++) {
            reversed[i] = x[31 - i];
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(
            new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, reversed))));
    }
}
