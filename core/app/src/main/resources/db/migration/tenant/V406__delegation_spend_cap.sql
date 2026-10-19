-- Per-grant cumulative spend cap (delegated-trust hardening).
--
-- V401 granted pay:* delegations bounded only by the bound wallet's balance.
-- This adds an optional cumulative ceiling on the GRANT itself: spend_cap_minor
-- is the lifetime cap across every token minted under it; spent_minor is the
-- running total, incremented inside the same row lock that gates x402
-- settlement (SELECT ... FOR UPDATE on the grant), so concurrent settlements
-- cannot overshoot.
--
-- NULL cap = unbounded up to the wallet balance (the V401 behavior - existing
-- rows migrate to NULL and keep it).

ALTER TABLE agent_delegation_grant
    ADD COLUMN spend_cap_minor BIGINT NULL CHECK (spend_cap_minor > 0),
    ADD COLUMN spent_minor     BIGINT NOT NULL DEFAULT 0 CHECK (spent_minor >= 0);
