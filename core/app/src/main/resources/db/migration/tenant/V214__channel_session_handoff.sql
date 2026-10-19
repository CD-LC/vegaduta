-- Live human handoff & operator inbox - the takeover primitive the 2026-08
-- agentic audit flagged as missing entirely: before this migration there was
-- no way for a human operator to take a live channel conversation away from
-- an agent, no record of who did so, and no persisted OUTBOUND side of any
-- conversation at all (channel_message_log was inbound-only, written at
-- exactly one site: ChannelSessionService#resolveOrCreate). The nearest
-- existing primitive was ChannelSession.unpair(), which silences the agent
-- but re-challenges the sender with a pairing code - a trust revocation, not
-- a handoff.
--
-- VERSION NUMBER
-- ---------------
-- application.yml's flyway.locations merges controlplane/ and tenant/ into ONE
-- version stream, so a number is free only when it is free in BOTH folders
-- (see V207/V175/V155/V145 for the same paragraph - and docs/ZERO-DOWNTIME-
-- DEPLOY-RULES.md for the 2026-07-31 production outage a duplicate V156
-- caused; a collision here is an OUTAGE, not a merge conflict). Checked at
-- authoring time (2026-08-18): tenant/'s highest values are V210, V211, V212,
-- V213 (agent_eval_dataset, landed by the concurrent evals workstream) and
-- V215 (google_credential_subject_key, pre-claimed by the per-user-credentials
-- workstream); controlplane/'s highest are V206 and V216 (claimed by a
-- concurrent moderation session). V214 is the slot explicitly assigned to
-- this handoff workstream and is absent from both folders.
--
-- WHY TENANT SCHEMA, NOT CONTROL PLANE
-- ------------------------------------
-- Both tables touched here already live in the tenant schema (channel_session
-- V13, channel_message_log V50), and everything added is customer conversation
-- state: who is talking to whom, what was said, which named operator stepped
-- in. All of it must vanish with TenantDataErasureService's DROP SCHEMA -
-- putting any of it in the control plane would be a right-to-erasure defect,
-- not a design preference (V207's phrasing, same reasoning). Per-subject
-- erasure also keeps working unchanged: ChannelSessionService#eraseDataSubject
-- deletes channel_message_log rows by (channel, external_id), and operator
-- replies written by OperatorInboxService carry the same (channel,
-- external_id) as the inbound rows they answer, so GDPR Article 17 erasure of
-- a sender now removes both sides of their conversation with no code change.
--
-- HANDOFF STATE, ON THE SESSION ROW ITSELF
-- ----------------------------------------
-- handoff_state is a column on channel_session rather than a separate
-- handoff table because a takeover is a STATE OF THE CONVERSATION, not an
-- event log: the inbound webhook path (ChannelSessionService#resolveOrCreate,
-- called by all 10 inbound entry points) must answer "may the agent speak?"
-- with the row it has ALREADY loaded, adding zero extra queries to the
-- hottest path in omnichannel. The three states:
--
--   AGENT          - normal operation, the agent replies (default, and the
--                    value every pre-existing row gets via DEFAULT).
--   AWAITING_HUMAN - escalated (by an operator today; by the designed
--                    request_human agent tool once its owning workstream
--                    lands it), waiting for an operator to claim it. The
--                    agent DELIBERATELY KEEPS REPLYING in this state: an
--                    unclaimed escalation must not silence the tenant's only
--                    responder - the operator may be minutes or hours away,
--                    and dead air is a worse failure than an imperfect agent
--                    answer. Suppression begins only when a human actually
--                    holds the conversation.
--   HUMAN          - a named operator holds the conversation; the agent (and
--                    the channel workflow trigger) must NOT act on inbound
--                    messages. Enforced fail-closed in
--                    ChannelSessionService#resolveOrCreate - see
--                    ConversationHeldByHumanException's javadoc for the
--                    mechanism and why enforcement lives there.
--
-- taken_over_by is the operator's Keycloak subject, VARCHAR(255) and
-- deliberately not an FK - the same opaque-string reasoning as
-- user_permission_grant.keycloak_user_id (V199): control_plane.app_user only
-- has rows for the original signup admin, so an FK would reject every
-- operator an admin later invited.
ALTER TABLE channel_session ADD COLUMN IF NOT EXISTS handoff_state VARCHAR(16) NOT NULL DEFAULT 'AGENT';
ALTER TABLE channel_session ADD COLUMN IF NOT EXISTS escalation_reason VARCHAR(500);
ALTER TABLE channel_session ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMPTZ;
ALTER TABLE channel_session ADD COLUMN IF NOT EXISTS taken_over_by VARCHAR(255);
ALTER TABLE channel_session ADD COLUMN IF NOT EXISTS taken_over_at TIMESTAMPTZ;

-- Same insurance V207 documents for agent_job.status: handoff_state is read
-- back through @Enumerated(STRING), so ONE typo'd value written by a hand-run
-- SQL fix would make every findAll() throw for the whole table. VOICE
-- sessions are additionally barred from ever leaving 'AGENT' at the service
-- layer (a live phone call cannot be handed off by a state flag - it is a
-- warm-transfer problem; see ChannelType's javadoc on why VOICE already
-- bypasses the approval gate for the same reason).
ALTER TABLE channel_session ADD CONSTRAINT channel_session_handoff_state_chk
    CHECK (handoff_state IN ('AGENT', 'AWAITING_HUMAN', 'HUMAN'));

-- The operator inbox's hot read is "every session in AWAITING_HUMAN or HUMAN,
-- newest activity first" - a vanishing fraction of channel_session once a
-- tenant has history, hence partial (same shape as V207's
-- idx_agent_job_live). V211's honest caveat applies here too and is worth
-- repeating: Hibernate binds the state values as parameters, so Postgres only
-- proves this index's predicate under a CUSTOM plan, not a generic one - fine
-- for a query this cheap either way.
CREATE INDEX IF NOT EXISTS idx_channel_session_handoff_live
    ON channel_session (last_message_at DESC)
    WHERE handoff_state IN ('AWAITING_HUMAN', 'HUMAN');
-- (Never CONCURRENTLY - Flyway wraps each migration in a transaction; V211.)

-- THE TRANSCRIPT GETS AN OUTBOUND SIDE
-- ------------------------------------
-- channel_message_log (V50) was append-only INBOUND: no agent reply and no
-- operator reply was ever persisted anywhere, which makes an operator inbox
-- unusable - a human cannot take over a conversation they can only see half
-- of. direction turns the log into a real two-way transcript. DEFAULT
-- 'INBOUND' is load-bearing twice over: it backfills every existing row with
-- the value that is true for all of them, and it lets the V50-era constructor
-- keep writing inbound rows unchanged.
--
-- author is NULL for inbound rows (the sender is already named by
-- external_id) and the operator's Keycloak subject for operator replies -
-- same opaque-VARCHAR-no-FK typing as taken_over_by above. Agent replies are
-- NOT yet logged: the outbound send happens in the per-channel webhook
-- controllers and channel adapters, which belong to other scopes - an honest,
-- documented gap (the operator sees every inbound message and every operator
-- reply, but not what the AGENT said before takeover), not a silent one.
ALTER TABLE channel_message_log ADD COLUMN IF NOT EXISTS direction VARCHAR(8) NOT NULL DEFAULT 'INBOUND';
ALTER TABLE channel_message_log ADD COLUMN IF NOT EXISTS author VARCHAR(255);

ALTER TABLE channel_message_log ADD CONSTRAINT channel_message_log_direction_chk
    CHECK (direction IN ('INBOUND', 'OUTBOUND'));

-- The transcript view reads one session's messages in time order. V50 only
-- ever indexed (channel, created_at) for the cross-sender "read all messages
-- from telegram" tool; a per-session read had nothing and would seq-scan.
CREATE INDEX IF NOT EXISTS idx_channel_message_log_session_created
    ON channel_message_log (channel_session_id, created_at);
