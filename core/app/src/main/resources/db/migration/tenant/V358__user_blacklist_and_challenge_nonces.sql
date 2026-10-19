-- Temporal user blacklist for abuse prevention
CREATE TABLE user_blacklist (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    reason VARCHAR(100) NOT NULL, -- MULTIPLE_FAILED_LOGIN_ATTEMPTS, SUSPICIOUS_BILLING, MANUAL_BLOCK, etc.
    blocked_by VARCHAR(50) NOT NULL, -- SYSTEM, ADMIN_MANUAL
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by UUID -- admin user ID if blocked by admin
);

-- One blacklist row per (tenant, user). See V357's note: a partial predicate
-- on expires_at is illegal (CURRENT_TIMESTAMP is not IMMUTABLE) and failed at
-- boot. Expiry is a read-time filter; re-block = upsert this row.
CREATE UNIQUE INDEX idx_user_blacklist_tenant_user_active ON user_blacklist(tenant_id, user_id);

-- Efficient lookup for expiration cleanup
CREATE INDEX idx_user_blacklist_expires ON user_blacklist(expires_at);

-- Fast checks during auth flow
CREATE INDEX idx_user_blacklist_lookup ON user_blacklist(tenant_id, user_id, expires_at);

-- Challenge nonces: temporary one-time-use tokens for fraud challenge verification
CREATE TABLE challenge_nonces (
    id VARCHAR(128) PRIMARY KEY, -- UUID v4 as string
    tenant_id UUID NOT NULL,
    user_id UUID,
    client_ip INET NOT NULL,
    challenge_type VARCHAR(20) NOT NULL, -- HCAPTCHA, EMAIL_OTP, SMS_OTP
    metadata JSONB, -- {reason: "AUTH_ABUSE", retry_count: 0, etc.}
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    verified_by_ip INET
);

-- Lookup active nonces for verification
CREATE INDEX idx_challenge_nonces_lookup ON challenge_nonces(tenant_id, id, expires_at);

-- Cleanup expired nonces efficiently
CREATE INDEX idx_challenge_nonces_expires ON challenge_nonces(expires_at);

-- Track verified challenges for rate limiting (don't reissue within X minutes)
CREATE INDEX idx_challenge_nonces_verified ON challenge_nonces(tenant_id, verified_at);
