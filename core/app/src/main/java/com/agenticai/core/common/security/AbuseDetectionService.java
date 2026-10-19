package com.agenticai.core.common.security;

import com.agenticai.core.tenancy.AllowUnlockedSchedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Core fraud detection service: analyzes requests for suspicious patterns and issues fraud
 * decisions (ALLOW, CHALLENGE, BLOCK) based on four threat vectors: 1. Authentication abuse (brute
 * force, credential stuffing) 2. API rate limiting & DoS 3. VPN/Proxy/Bot detection 4.
 * Payment/billing fraud (no analysis implemented yet - always ALLOW)
 *
 * <p>Only active with {@code app.fraud-detection.enabled=true} (JAVA-005, see {@link
 * FraudDetectionConfig}). Deliberately NOT class-level {@code @Transactional(readOnly = true)} any
 * more: the decision path is a handful of independent reads, and every write goes through {@link
 * AbuseEventRecorder} (its own transaction) and is failure-isolated, so a failed audit INSERT can
 * no longer turn a BLOCK into an ALLOW.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = FraudDetectionConfig.ENABLED_PROPERTY, havingValue = "true")
public class AbuseDetectionService {
    private final IpBlacklistRepository ipBlacklistRepo;
    private final UserBlacklistRepository userBlacklistRepo;
    private final ChallengeNonceRepository challengeNonceRepo;
    private final HCaptchaClient hCaptchaClient;
    private final AbuseEventRecorder recorder;

    // Configuration thresholds
    @Value("${fraud-detection.auth.max-attempts:5}")
    private int authMaxAttempts;

    @Value("${fraud-detection.auth.block-threshold:10}")
    private int authBlockThreshold;

    @Value("${fraud-detection.api.soft-limit:60}")
    private int apiSoftLimit;

    @Value("${fraud-detection.api.hard-limit:100}")
    private int apiHardLimit;

    @Value("${fraud-detection.vpn.score-threshold:0.7}")
    private double vpnScoreThreshold;

    @Value("${fraud-detection.auto-block-duration-minutes:60}")
    private int autoBlockDurationMinutes;

    // In-memory rate limiting buckets (keyed by tenant:ip or tenant:userid)
    private final Map<String, RateLimitBucket> rateLimitBuckets = new ConcurrentHashMap<>();

    /**
     * Analyze a request for fraud and return a decision. This is the main entry point for fraud
     * detection checks.
     */
    public FraudDecision analyzeRequest(ClientContext clientCtx, RequestContext requestCtx) {
        if (!requestCtx.shouldCheckForFraud()) {
            return FraudDecision.ALLOW;
        }

        try {
            // 1. Fast path: check blacklists
            if (isIpBlacklisted(clientCtx.tenantId, clientCtx.ip)) {
                logAbuseEvent(clientCtx, null, "IP_BLACKLIST_BLOCKED", FraudDecision.BLOCK);
                return FraudDecision.BLOCK;
            }

            if (clientCtx.userId != null
                    && isUserBlacklisted(clientCtx.tenantId, clientCtx.userId)) {
                logAbuseEvent(clientCtx, null, "USER_BLACKLIST_BLOCKED", FraudDecision.BLOCK);
                return FraudDecision.BLOCK;
            }

            // 2. Route to appropriate analyzer
            FraudDecision decision = FraudDecision.ALLOW;

            if (requestCtx.isAuthEndpoint) {
                decision = analyzeAuthRequest(clientCtx);
            } else if (requestCtx.isApiEndpoint) {
                decision = analyzeApiRequest(clientCtx, requestCtx);
            }
            // Billing endpoints: no billing-specific analysis exists (the old
            // analyzeBillingRequest was a placeholder returning ALLOW); they
            // still get the VPN check below when high-risk.

            // 3. Supplemental VPN check for high-risk endpoints
            if (decision == FraudDecision.ALLOW && requestCtx.isHighRiskEndpoint) {
                VpnLikelihoodScore vpnScore = analyzeVpnLikelihood(clientCtx);
                if (vpnScore.score >= vpnScoreThreshold) {
                    log.warn(
                            "VPN/Datacenter IP detected for high-risk endpoint: {} (score: {})",
                            clientCtx.ip,
                            vpnScore.score);
                    decision = FraudDecision.CHALLENGE;
                }
            }

            // 4. Log the event
            if (decision.isSuspicious()) {
                String reason = getReasonForDecision(decision, requestCtx);
                logAbuseEvent(clientCtx, null, reason, decision);
            }

            return decision;
        } catch (Exception e) {
            log.error("Error during fraud analysis for {}", clientCtx, e);
            // Fail open: allow on error (don't block legitimate traffic due to bugs)
            return FraudDecision.ALLOW;
        }
    }

    /** Analyze authentication endpoint requests for brute force / credential stuffing. */
    private FraudDecision analyzeAuthRequest(ClientContext clientCtx) {
        String bucketKey = clientCtx.tenantId + ":auth:" + clientCtx.ip;
        RateLimitBucket bucket = getRateLimitBucket(bucketKey, Duration.ofMinutes(15));
        bucket.recordRequest();

        int attemptCount = bucket.getCurrentCount();

        // Escalation logic:
        // 1-4 attempts: ALLOW (normal user retrying password)
        // 5-9 attempts: CHALLENGE (suspicious, but might be a user)
        // 10+ attempts: BLOCK (clear brute force)

        if (attemptCount >= authBlockThreshold) {
            // Auto-blacklist aggressive attackers
            if (attemptCount % 10 == 0) { // Only blacklist once per 10 attempts
                try {
                    recorder.blacklistIp(
                            clientCtx.tenantId,
                            clientCtx.ip,
                            "AUTH_BRUTE_FORCE_DETECTED",
                            autoBlockDurationMinutes);
                } catch (RuntimeException e) {
                    // The BLOCK below stands regardless of whether the write landed.
                    log.warn("Auto-blacklist write failed; request is still blocked", e);
                }
            }
            return FraudDecision.BLOCK;
        }

        if (attemptCount >= authMaxAttempts) {
            return FraudDecision.CHALLENGE;
        }

        return FraudDecision.ALLOW;
    }

    /** Analyze API endpoints for rate limiting and DoS patterns. */
    private FraudDecision analyzeApiRequest(ClientContext clientCtx, RequestContext requestCtx) {
        String bucketKey = clientCtx.tenantId + ":api:" + clientCtx.ip;
        RateLimitBucket bucket = getRateLimitBucket(bucketKey, Duration.ofMinutes(1));
        bucket.recordRequest();

        int requestCount = bucket.getCurrentCount();
        double requestRate = bucket.getCurrentRate();

        // Hard limit: >100 req/min = BLOCK
        if (requestCount > apiHardLimit) {
            return FraudDecision.BLOCK;
        }

        // Soft limit: 60-100 req/min = CHALLENGE
        if (requestCount > apiSoftLimit) {
            return FraudDecision.CHALLENGE;
        }

        return FraudDecision.ALLOW;
    }

    /** Analyze VPN/Proxy/Bot likelihood based on IP reputation and fingerprinting. */
    private VpnLikelihoodScore analyzeVpnLikelihood(ClientContext clientCtx) {
        double score = 0.0;
        String ipType = "residential";
        String asn = "unknown";
        String country = "unknown";

        // Heuristic 1: Datacenter IP detection (0-1.0 scale)
        // In production, integrate with MaxMind GeoIP2 or IP Qualityscore
        String ipClass = classifyIp(clientCtx.ip);
        if ("datacenter".equals(ipClass)) {
            score += 0.7;
            ipType = "datacenter";
        } else if ("hosting".equals(ipClass)) {
            score += 0.5;
            ipType = "hosting";
        }

        // Heuristic 2: Headless browser detection
        if (isHeadlessBrowser(clientCtx.userAgent)) {
            score += 0.6;
        }

        // Clamp score to 0.0-1.0
        score = Math.min(1.0, score);

        return new VpnLikelihoodScore(score, ipType, asn, country, "Unknown ISP");
    }

    /**
     * Simple IP classification (in production, use MaxMind/IP Qualityscore): {@code private} for
     * RFC 1918 / ULA addresses, {@code loopback} for loopback, link-local, unspecified and
     * multicast addresses, {@code unknown} for an address that cannot be classified, and {@code
     * residential} for everything else.
     *
     * <p>Uses the JDK's own address classification rather than string prefixes: the old {@code
     * startsWith("172.")} test treated all of 172.0.0.0/8 as private when only 172.16.0.0/12 is.
     */
    static String classifyIp(InetAddress ip) {
        if (ip == null) {
            return "unknown";
        }
        // TODO: Integrate with MaxMind GeoIP2 or IP Qualityscore API
        if (ip.isLoopbackAddress()
                || ip.isLinkLocalAddress()
                || ip.isAnyLocalAddress()
                || ip.isMulticastAddress()) {
            return "loopback";
        }
        // isSiteLocalAddress() covers 10/8, 172.16/12 and 192.168/16 (and IPv6 site-local);
        // IPv6 unique-local (fc00::/7) is checked by hand because the JDK does not flag it.
        if (ip.isSiteLocalAddress() || isIpv6UniqueLocal(ip)) {
            return "private";
        }
        // TODO: Query IP reputation service to detect datacenter/hosting IPs
        // For demo, assume non-private IPs are residential
        return "residential";
    }

    /**
     * {@link #classifyIp(InetAddress)} for a textual address. Only IP literals are accepted; a host
     * name or any unparsable text yields {@code unknown} and never triggers a DNS lookup.
     */
    static String classifyIp(String ip) {
        InetAddress parsed = parseIpLiteral(ip);
        return parsed == null ? "unknown" : classifyIp(parsed);
    }

    private static boolean isIpv6UniqueLocal(InetAddress ip) {
        if (!(ip instanceof Inet6Address)) {
            return false;
        }
        return (ip.getAddress()[0] & 0xfe) == 0xfc;
    }

    /**
     * Parses a dotted-quad IPv4 or bracket-less IPv6 literal without ever consulting DNS: {@link
     * InetAddress#getByName} resolves anything that is not a literal, so the shape is checked first
     * and only a well-formed literal is handed to it.
     */
    private static InetAddress parseIpLiteral(String text) {
        if (text == null) {
            return null;
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (IPV4_LITERAL.matcher(s).matches()) {
            for (String octet : s.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        } else if (!IPV6_LITERAL.matcher(s).matches()) {
            return null;
        }
        try {
            // Brackets make the JDK treat the text strictly as an IPv6 literal: a malformed one
            // fails with UnknownHostException instead of falling back to a DNS lookup.
            return InetAddress.getByName(s.indexOf(':') >= 0 ? "[" + s + "]" : s);
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Hex groups and colons, optionally with an embedded dotted IPv4 tail; must contain a colon.
     */
    private static final Pattern IPV6_LITERAL =
            Pattern.compile("[0-9a-fA-F:]*:[0-9a-fA-F:]*(\\d{1,3}(\\.\\d{1,3}){3})?");

    /** Detect headless browser User-Agents (Selenium, Puppeteer, etc.). */
    private boolean isHeadlessBrowser(String userAgent) {
        if (userAgent == null) {
            return false;
        }

        String ua = userAgent.toLowerCase(Locale.ROOT);
        return ua.contains("headless")
                || ua.contains("selenium")
                || ua.contains("puppeteer")
                || ua.contains("webdriver")
                || ua.contains("phantomjs")
                || ua.contains("playwrightchromium")
                || !ua.contains("mozilla"); // No Mozilla signature = suspicious
    }

    /** Generate a challenge nonce for fraud verification. */
    @Transactional
    public String generateChallengeNonce(UUID tenantId, UUID userId, InetAddress clientIp) {
        String nonce = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));

        ChallengeNonce challenge =
                new ChallengeNonce(nonce, tenantId, clientIp, "HCAPTCHA", expiresAt);
        challenge.setUserId(userId);

        challengeNonceRepo.save(challenge);
        return nonce;
    }

    /** Verify a challenge nonce and hCaptcha token. */
    @Transactional
    public boolean verifyChallengeNonce(String nonce, String hcaptchaToken, String clientIpStr)
            throws HCaptchaClient.HCaptchaException {

        ChallengeNonce challenge =
                challengeNonceRepo
                        .findValidNonce(nonce, null)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid or expired challenge nonce"));

        // Verify hCaptcha token
        boolean isValid = hCaptchaClient.verify(hcaptchaToken, clientIpStr).isSuccess();

        if (!isValid) {
            return false;
        }

        // Mark nonce as verified
        challenge.setVerifiedAt(Instant.now());
        try {
            challenge.setVerifiedByIp(InetAddress.getByName(clientIpStr));
        } catch (Exception e) {
            log.warn("Failed to parse verified IP: {}", clientIpStr);
        }

        challengeNonceRepo.save(challenge);
        return true;
    }

    /** Check if an IP is currently blacklisted. */
    public boolean isIpBlacklisted(UUID tenantId, InetAddress ip) {
        return ipBlacklistRepo.isBlacklisted(tenantId, ip);
    }

    /** Check if a user is currently blacklisted. */
    public boolean isUserBlacklisted(UUID tenantId, UUID userId) {
        return userBlacklistRepo.isBlacklisted(tenantId, userId);
    }

    /**
     * Manually blacklist an IP (admin action). Delegates to the recorder so the write has a real
     * read-write transaction.
     */
    public void blacklistIp(UUID tenantId, InetAddress ip, String reason, int durationMinutes) {
        recorder.blacklistIp(tenantId, ip, reason, durationMinutes);
    }

    /** Manually blacklist a user (admin action). */
    @Transactional
    public void blacklistUser(
            UUID tenantId, UUID userId, String reason, int durationMinutes, UUID adminId) {
        UserBlacklist blacklist =
                new UserBlacklist(
                        tenantId,
                        userId,
                        reason,
                        "ADMIN_MANUAL",
                        Instant.now().plus(Duration.ofMinutes(durationMinutes)));
        blacklist.setCreatedBy(adminId);

        userBlacklistRepo.save(blacklist);
        log.warn(
                "User blacklisted: {} for reason: {} ({}min, by admin: {})",
                userId,
                reason,
                durationMinutes,
                adminId);
    }

    /** Get or create a rate limit bucket. */
    private RateLimitBucket getRateLimitBucket(String key, Duration window) {
        return rateLimitBuckets.computeIfAbsent(key, k -> new RateLimitBucket(window));
    }

    /**
     * Log an abuse event (own transaction, see {@link AbuseEventRecorder}). Failure-isolated: the
     * decision never depends on the audit write.
     */
    private void logAbuseEvent(
            ClientContext clientCtx, String challengeNonce, String reason, FraudDecision decision) {
        try {
            recorder.recordEvent(clientCtx, challengeNonce, reason, decision);
        } catch (RuntimeException e) {
            log.warn(
                    "Abuse event write failed ({} / {}); decision unaffected", reason, decision, e);
        }
    }

    /** Determine a human-readable reason for a fraud decision. */
    private String getReasonForDecision(FraudDecision decision, RequestContext requestCtx) {
        if (requestCtx.isAuthEndpoint) {
            return "AUTH_ABUSE_DETECTED";
        } else if (requestCtx.isApiEndpoint) {
            return "API_RATE_LIMIT_EXCEEDED";
        } else if (requestCtx.isBillingEndpoint) {
            return "BILLING_FRAUD_SUSPECTED";
        }
        return "SUSPICIOUS_ACTIVITY";
    }

    /** Clear in-memory rate limit buckets periodically to prevent memory leak. */
    @AllowUnlockedSchedule(
            reason = "Evicts expired buckets from this JVM's own in-memory rateLimitBuckets map.")
    @Scheduled(fixedDelay = 600_000) // Run every 10 minutes
    public void clearExpiredRateLimitBuckets() {
        int initialSize = rateLimitBuckets.size();

        rateLimitBuckets
                .entrySet()
                .removeIf(
                        entry -> {
                            RateLimitBucket bucket = entry.getValue();
                            return bucket.getTimeRemainingSeconds() <= 0;
                        });

        if (rateLimitBuckets.size() < initialSize) {
            log.debug(
                    "Cleared {} expired rate limit buckets", initialSize - rateLimitBuckets.size());
        }
    }
}
