-- x402 settlement reference (EVM facilitator hook).
--
-- V405's 'exact' scheme verifies the EIP-3009 authorization but leaves
-- on-chain settlement to a facilitator. When app.x402.facilitator-url is
-- configured the seller POSTs the payment to <url>/settle and records the
-- returned transaction hash / settlement id here - the audit trail for "who
-- moved the money on-chain".

ALTER TABLE control_plane.x402_payment
    ADD COLUMN settlement_ref VARCHAR(160) NULL;
