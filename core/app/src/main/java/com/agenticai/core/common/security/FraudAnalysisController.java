package com.agenticai.core.common.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST API controller for fraud detection operations.
 *
 * Public endpoints (frontend can call):
 * - POST /api/internal/abuse/verify-captcha - Verify hCaptcha token
 *
 * Admin-only endpoints:
 * - GET /api/internal/abuse/status - Get tenant abuse status
 * - POST /api/internal/abuse/blacklist/ip - Manually blacklist an IP
 * - DELETE /api/internal/abuse/blacklist/ip/{ip} - Remove IP from blacklist
 * - POST /api/internal/abuse/blacklist/user - Manually blacklist a user
 * - DELETE /api/internal/abuse/blacklist/user/{userId} - Remove user from blacklist
 */
@Slf4j
@ConditionalOnProperty(name = FraudDetectionConfig.ENABLED_PROPERTY, havingValue = "true")
@RestController
@RequestMapping("/api/internal/abuse")
@RequiredArgsConstructor
public class FraudAnalysisController {
    /** Tenant-admin (or platform-admin) only: these endpoints read and mutate tenant-wide block lists. */
    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    private final AbuseDetectionService abuseDetectionService;
    private final ChallengeNonceRepository challengeNonceRepo;
    private final IpBlacklistRepository ipBlacklistRepo;
    private final UserBlacklistRepository userBlacklistRepo;
    private final HCaptchaClient hCaptchaClient;

    /**
     * Verify hCaptcha token and issue a challenge-pass token.
     * Called by frontend after user completes CAPTCHA challenge.
     */
    @PostMapping("/verify-captcha")
    public ResponseEntity<Map<String, Object>> verifyCaptcha(
            @RequestBody HCaptchaVerifyRequest req,
            HttpServletRequest httpReq) {

        UUID tenantId = FraudTenantIds.currentTenant().orElse(null);
        if (tenantId == null) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Missing tenant context"));
        }

        // Validate inputs
        if (req.getNonce() == null || req.getNonce().isEmpty()) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Missing challenge nonce"));
        }

        if (req.getToken() == null || req.getToken().isEmpty()) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Missing hCaptcha token"));
        }

        try {
            // Find and validate the challenge nonce
            ChallengeNonce nonce = challengeNonceRepo.findValidNonce(req.getNonce(), tenantId)
                    .orElse(null);

            if (nonce == null) {
                log.warn("Invalid or expired challenge nonce");
                return ResponseEntity.status(400)
                        .body(Map.of("error", "Invalid or expired challenge. Please try again."));
            }

            // Verify the hCaptcha token with hCaptcha servers
            String clientIp = extractClientIp(httpReq);
            HCaptchaClient.HCaptchaVerifyResponse hcaptchaResponse =
                    hCaptchaClient.verify(req.getToken(), clientIp);

            if (!hcaptchaResponse.isSuccess()) {
                log.warn("hCaptcha verification failed: {}", hcaptchaResponse.getErrorCodes());
                return ResponseEntity.status(400)
                        .body(Map.of(
                            "error", "CAPTCHA verification failed",
                            "message", "Please try the CAPTCHA again"
                        ));
            }

            // Mark nonce as verified
            nonce.setVerifiedAt(Instant.now());
            try {
                nonce.setVerifiedByIp(InetAddress.getByName(clientIp));
            } catch (Exception e) {
                log.warn("Failed to parse client IP for verification: {}", clientIp);
            }
            challengeNonceRepo.save(nonce);

            // Generate a challenge-pass token (JWT or opaque token in production)
            String passToken = generateChallengePassToken(tenantId, nonce);

            log.info("Challenge verified successfully");

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Challenge verified successfully",
                "challenge_pass_token", passToken,
                "expires_in", 3600 // 1 hour in seconds
            ));

        } catch (HCaptchaClient.HCaptchaException e) {
            log.error("hCaptcha verification error", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Verification service error. Please try again."));
        } catch (Exception e) {
            log.error("Unexpected error during challenge verification", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Get abuse status and statistics for the tenant.
     */
    @GetMapping("/status")
    @PreAuthorize(TENANT_ADMIN)
    public ResponseEntity<Map<String, Object>> getAbuseStatus(HttpServletRequest req) {
        UUID tenantId = FraudTenantIds.currentTenant().orElse(null);
        if (tenantId == null) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Missing tenant context"));
        }

        String clientIp = extractClientIp(req);

        return ResponseEntity.ok(Map.of(
            "tenant_id", tenantId.toString(),
            "client_ip", clientIp,
            "ip_blacklisted", abuseDetectionService.isIpBlacklisted(tenantId, parseIp(clientIp)),
            "active_ip_blocks", ipBlacklistRepo.countActive(tenantId),
            "active_user_blocks", userBlacklistRepo.countActive(tenantId),
            "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Admin: Manually blacklist an IP.
     */
    @PostMapping("/blacklist/ip")
    @PreAuthorize(TENANT_ADMIN)
    public ResponseEntity<Map<String, Object>> blacklistIp(
            @RequestBody BlacklistIpRequest req,
            HttpServletRequest httpReq) {

        UUID tenantId = FraudTenantIds.currentTenant().orElse(null);
        UUID adminId = FraudTenantIds.currentUser().orElse(null);

        if (tenantId == null || adminId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        }

        try {
            InetAddress ipAddress = InetAddress.getByName(req.getIp());

            abuseDetectionService.blacklistIp(
                tenantId, ipAddress, req.getReason(),
                req.getDurationMinutes() > 0 ? req.getDurationMinutes() : 60
            );

            log.info("IP {} blacklisted by admin {} for reason: {}",
                    req.getIp(), adminId, req.getReason());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "IP blacklisted successfully",
                "ip", req.getIp(),
                "expires_in_minutes", req.getDurationMinutes()
            ));
        } catch (Exception e) {
            log.error("Error blacklisting IP", e);
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Invalid IP address"));
        }
    }

    /**
     * Admin: Manually blacklist a user.
     */
    @PostMapping("/blacklist/user")
    @PreAuthorize(TENANT_ADMIN)
    public ResponseEntity<Map<String, Object>> blacklistUser(
            @RequestBody BlacklistUserRequest req,
            HttpServletRequest httpReq) {

        UUID tenantId = FraudTenantIds.currentTenant().orElse(null);
        UUID adminId = FraudTenantIds.currentUser().orElse(null);

        if (tenantId == null || adminId == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Unauthorized"));
        }

        try {
            UUID userId = UUID.fromString(req.getUserId());

            abuseDetectionService.blacklistUser(
                tenantId, userId, req.getReason(),
                req.getDurationMinutes() > 0 ? req.getDurationMinutes() : 60,
                adminId
            );

            log.info("User {} blacklisted by admin {} for reason: {}",
                    userId, adminId, req.getReason());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User blacklisted successfully",
                "user_id", userId.toString(),
                "expires_in_minutes", req.getDurationMinutes()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "Invalid user ID"));
        } catch (Exception e) {
            log.error("Error blacklisting user", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Generate a challenge-pass token (opaque token or JWT in production).
     */
    private String generateChallengePassToken(UUID tenantId, ChallengeNonce nonce) {
        // In production, generate a signed JWT or opaque token that can be validated on next request
        // For now, return a simple token format: tenant_id.nonce_id.timestamp
        return tenantId.toString().substring(0, 8) + "." +
               nonce.getId().substring(0, 8) + "." +
               System.currentTimeMillis();
    }

    /**
     * Extract client IP from request, handling proxies.
     */
    private String extractClientIp(HttpServletRequest req) {
        String xForwardedFor = req.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = req.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return req.getRemoteAddr();
    }

    /**
     * Parse IP string to InetAddress.
     */
    private InetAddress parseIp(String ipStr) {
        try {
            return InetAddress.getByName(ipStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IP: " + ipStr);
        }
    }

    // ========== DTOs ==========

    public static class HCaptchaVerifyRequest {
        @JsonProperty("nonce")
        private String nonce;

        @JsonProperty("token")
        private String token;

        public String getNonce() { return nonce; }
        public void setNonce(String nonce) { this.nonce = nonce; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class BlacklistIpRequest {
        @JsonProperty("ip")
        private String ip;

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("duration_minutes")
        private int durationMinutes = 60;

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    }

    public static class BlacklistUserRequest {
        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("duration_minutes")
        private int durationMinutes = 60;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    }
}
