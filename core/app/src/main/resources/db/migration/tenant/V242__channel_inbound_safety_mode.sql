-- Inbound channel safety scan (P1 developer-extensibility, 2026-09-23) -
-- a per-channel, per-tenant switch on the channel routing row controlling
-- what happens when the platform's jailbreak/prompt-injection detector
-- flags an INBOUND channel message before it reaches an agent.
--
--   OFF  - no scan (tenant opts out explicitly)
--   FLAG - default. The message is delivered normally, but the session is
--          escalateToHuman()'d (AWAITING_HUMAN) so it surfaces in the
--          operator inbox, and the flag is audit-logged. Fail-open on
--          purpose: pattern-matching has false positives and silencing a
--          real customer is the worse failure.
--   HOLD - strict. The session is takeOver("system:safety-scan")'d, which
--          suppresses the agent for this AND subsequent messages until an
--          operator releases it - the same mechanism live-human handoff
--          already uses, so no new exception plumbing exists anywhere.
--
-- Lives on channel_default_agent because inbound safety is channel
-- routing policy, not global: a WhatsApp Business number facing the
-- public internet wants HOLD; an internal Slack channel may want OFF.
--
-- Version V242: controlplane/ and tenant/ share ONE Flyway version stream.
-- Highest taken at write time: tenant/V241 + controlplane/V237.

ALTER TABLE channel_default_agent
    ADD COLUMN IF NOT EXISTS inbound_safety_mode VARCHAR(16) NOT NULL DEFAULT 'FLAG';

ALTER TABLE channel_default_agent
    DROP CONSTRAINT IF EXISTS channel_default_agent_safety_chk;

ALTER TABLE channel_default_agent
    ADD CONSTRAINT channel_default_agent_safety_chk
    CHECK (inbound_safety_mode IN ('OFF', 'FLAG', 'HOLD'));
