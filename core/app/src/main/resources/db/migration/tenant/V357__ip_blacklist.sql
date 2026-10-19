-- Temporal IP blacklist for abuse prevention: IPs blocked due to fraud/abuse
CREATE TABLE ip_blacklist (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    ip_address INET NOT NULL,
    reason VARCHAR(100) NOT NULL, -- AUTH_BRUTE_FORCE_DETECTED, RATE_LIMIT_EXCEEDED, SUSPICIOUS_PATTERN, MANUAL_BLOCK, etc.
    blocked_by VARCHAR(50) NOT NULL, -- SYSTEM, ADMIN_MANUAL
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by UUID -- admin user ID if blocked by admin
);

-- One blacklist row per (tenant, ip). The original draft made this a partial
-- index (WHERE expires_at > CURRENT_TIMESTAMP), but Postgres forbids
-- non-IMMUTABLE functions in index predicates — it failed at boot on a real
-- database. Expiry is read-time (queries filter expires_at > now()); re-block
-- after expiry means UPDATE/upsert this row, not a second INSERT — history of
-- past blocks lives in abuse_events (V356), not here.
CREATE UNIQUE INDEX idx_ip_blacklist_tenant_ip_active ON ip_blacklist(tenant_id, ip_address);

-- Efficient lookup for expiration cleanup
CREATE INDEX idx_ip_blacklist_expires ON ip_blacklist(expires_at);

-- Fast checks by IP during request handling
CREATE INDEX idx_ip_blacklist_lookup ON ip_blacklist(tenant_id, ip_address, expires_at);
