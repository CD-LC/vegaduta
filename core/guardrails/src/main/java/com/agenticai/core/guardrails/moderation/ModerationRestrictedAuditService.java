package com.agenticai.core.guardrails.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase P6 - the RESTRICTED audit path. Everything an {@link
 * ExternalSafetyClassifier} match produces is recorded HERE and only here,
 * never through {@code AuditLogService} (the general log the tenant-admin UI
 * reads) and never in an application log line carrying category or content.
 *
 * <p>The single general-log line this class emits is deliberately
 * content-free and category-free: it says only that a restricted event was
 * recorded and gives the row id, so operators know the path fired without the
 * log file itself becoming a leak of the most sensitive possible signal.
 *
 * <p>This exists so that when a real classifier is eventually wired (with
 * counsel + a vendor), the routing is already correct: matches do not scatter
 * into general observability. See {@link ExternalSafetyClassifier} and {@link
 * ModerationRestrictedAudit} for the legal/vendor caveats.
 */
@Service
public class ModerationRestrictedAuditService {

    private static final Logger log = LoggerFactory.getLogger(ModerationRestrictedAuditService.class);

    private final ModerationRestrictedAuditRepository repository;

    public ModerationRestrictedAuditService(ModerationRestrictedAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a highest-stakes match. Returns the persisted row id purely as a
     * correlation handle for the caller/operator - nothing sensitive.
     */
    public UUID record(ModerationContext context, ExternalSafetyVerdict verdict, String content) {
        ModerationRestrictedAudit entry = new ModerationRestrictedAudit(
            context != null ? context.tenantId() : null,
            context != null ? context.agentId() : null,
            context != null ? context.sessionId() : null,
            verdict.category(),
            verdict.confidence(),
            verdict.opaqueReference(),
            sha256Hex(content)
        );
        ModerationRestrictedAudit saved = repository.save(entry);
        // Content-free, category-free by design - see class javadoc.
        log.warn("RESTRICTED moderation event recorded (id={}). Handled off the general audit path; "
            + "follow the counsel-approved restricted-handling procedure.", saved.getId());
        return saved.getId();
    }

    private String sha256Hex(String content) {
        if (content == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required JDK algorithm; unreachable in practice.
            return null;
        }
    }
}
