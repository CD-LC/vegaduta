-- Fraud detection audit log: tracks all abuse detection decisions (brute force, rate limit, VPN, etc.)
CREATE TABLE abuse_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL, -- AUTH_BRUTE_FORCE, VPN_AUTH_ABUSE, API_RATE_LIMIT_EXCEEDED, BILLING_VELOCITY_SPIKE, etc.
    severity VARCHAR(20) NOT NULL, -- LOW, MEDIUM, HIGH, CRITICAL
    client_ip INET NOT NULL,
    user_id UUID,
    user_agent TEXT,
    tls_ja3_fingerprint VARCHAR(100),
    reason TEXT,
    decision VARCHAR(20) NOT NULL, -- ALLOW, CHALLENGE, BLOCK
    challenge_nonce VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_abuse_events_tenant_ip_created ON abuse_events(tenant_id, client_ip, created_at DESC);
CREATE INDEX idx_abuse_events_tenant_user_created ON abuse_events(tenant_id, user_id, created_at DESC);
CREATE INDEX idx_abuse_events_event_type ON abuse_events(event_type);
CREATE INDEX idx_abuse_events_severity ON abuse_events(severity);
