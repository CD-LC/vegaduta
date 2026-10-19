-- #913/#914 (Kafka/queue reliability hardening) - WorkflowEventOutboxRelay's
-- own javadoc has flagged this since Phase 0: "a dead-letter path for a row
-- that fails forever is a Section 9 hardening item." Without these columns,
-- a permanently-broken row (bad payload, a topic ACL problem, anything that
-- fails on every attempt) sat in the unpublished set forever, re-attempted
-- on every single 5s poll, forever, with zero visibility - silently wasting
-- relay cycles and never surfacing as an actionable problem anywhere.
--
-- retry_count is incremented by WorkflowEventOutboxRelay each time a publish
-- attempt fails; once it reaches WorkflowEventOutboxRelay.MAX_RETRIES the row
-- is excluded from the relay's poll query (idx_workflow_event_outbox_unpublished
-- below is narrowed to match) and a PlatformIssue is raised once, at the
-- moment the row crosses that threshold - see WorkflowEventOutboxRelay's own
-- javadoc for the exact mechanics. last_error keeps the most recent
-- failure's message for whoever triages that issue, without needing to
-- correlate back through app logs.
ALTER TABLE workflow_event_outbox ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_event_outbox ADD COLUMN last_error TEXT;

-- Replaces the plain published_at-only partial index (V19) - the relay's
-- poll query now also excludes dead-lettered rows (retry_count >= the cap),
-- so the index needs to cover both predicates to stay useful instead of
-- silently degrading to a sequential scan once dead-lettered rows
-- accumulate.
DROP INDEX idx_workflow_event_outbox_unpublished;
CREATE INDEX idx_workflow_event_outbox_unpublished ON workflow_event_outbox (published_at, retry_count)
    WHERE published_at IS NULL;
