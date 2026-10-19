package com.agenticai.core.common.security;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of fraud detection, in its own bean so every write goes
 * through the Spring proxy with its own read-write transaction (JAVA-005).
 * Previously these writes were a {@code @Transactional private} method and a
 * self-invoked {@code blacklistIp} inside a class-level
 * {@code @Transactional(readOnly = true)} service: the INSERT failed in the
 * read-only transaction, and the caller's catch turned a BLOCK decision into
 * ALLOW. {@code REQUIRES_NEW} also keeps a failed audit write from marking an
 * unrelated outer transaction rollback-only.
 */
@Service
@ConditionalOnProperty(name = FraudDetectionConfig.ENABLED_PROPERTY, havingValue = "true")
public class AbuseEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(AbuseEventRecorder.class);

    private final AbuseEventRepository abuseEventRepo;
    private final IpBlacklistRepository ipBlacklistRepo;

    public AbuseEventRecorder(AbuseEventRepository abuseEventRepo, IpBlacklistRepository ipBlacklistRepo) {
        this.abuseEventRepo = abuseEventRepo;
        this.ipBlacklistRepo = ipBlacklistRepo;
    }

    /** Persists one abuse event. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvent(ClientContext clientCtx, String challengeNonce, String reason, FraudDecision decision) {
        AbuseEvent event = new AbuseEvent(
                clientCtx.tenantId, reason, decision == FraudDecision.BLOCK ? "CRITICAL" : "HIGH",
                clientCtx.ip, reason, decision.name()
        );
        event.setUserId(clientCtx.userId);
        event.setUserAgent(clientCtx.userAgent);
        event.setTlsJa3Fingerprint(clientCtx.tlsFingerprint);
        event.setChallengeNonce(challengeNonce);
        abuseEventRepo.save(event);
    }

    /** Blacklists an IP unless an active entry already exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void blacklistIp(UUID tenantId, InetAddress ip, String reason, int durationMinutes) {
        if (ipBlacklistRepo.isBlacklisted(tenantId, ip)) {
            return;
        }
        IpBlacklist blacklist = new IpBlacklist(
                tenantId, ip, reason, "SYSTEM",
                Instant.now().plus(Duration.ofMinutes(durationMinutes))
        );
        ipBlacklistRepo.save(blacklist);
        log.warn("IP blacklisted for reason: {} ({}min)", reason, durationMinutes);
    }
}
