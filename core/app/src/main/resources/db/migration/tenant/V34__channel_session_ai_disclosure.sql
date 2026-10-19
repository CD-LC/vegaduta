-- Autonomy/Guardrails Strategy - AI-governance transparency requirement
-- (EU AI Act Art. 50 / Singapore's 2026 Model AI Governance Framework for
-- Agentic AI): track whether the one-time "you're chatting with an AI"
-- disclosure has already been sent for this session, same shape as
-- pairing_notice_sent (V13). Existing rows default to TRUE so this doesn't
-- retroactively inject a disclosure into conversations already in progress
-- when this migration runs - only brand-new sessions created after this
-- migration start at FALSE (see ChannelSession's constructor).

ALTER TABLE channel_session ADD COLUMN ai_disclosure_sent BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE channel_session ALTER COLUMN ai_disclosure_sent DROP DEFAULT;
