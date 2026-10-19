-- Interactive Workflow Designer PRD, Phase 0 / Section 9 - the transactional
-- outbox for the event-driven execution backbone. A row here is written in
-- the same Postgres transaction as the fact it represents (e.g. "an inbound
-- WhatsApp message was received"); WorkflowEventOutboxRelay (a @Scheduled
-- job, @Profile("workflow"), using the same TenantSchemaIterator every other
-- cross-tenant background job in this codebase already uses) separately
-- polls published_at IS NULL rows and publishes them to the Kafka
-- "workflow.events" topic, then stamps published_at. This two-step
-- write-then-relay is what avoids the classic dual-write bug (DB commit
-- succeeds, Kafka publish fails, event silently lost) without needing a
-- distributed transaction across Postgres and Kafka.
--
-- run_id is VARCHAR, not a foreign key - Phase 0 has no WorkflowRun entity
-- yet (that arrives in Phase 1); today it is just a freshly generated
-- correlation id per triggering event. workflow_id/node_id are nullable for
-- the same reason: Phase 0's only producer (TwilioWebhookController) emits a
-- run-level event with neither.
CREATE TABLE workflow_event_outbox (
    id            UUID PRIMARY KEY,
    event_type    VARCHAR(200) NOT NULL,
    workflow_id   UUID,
    run_id        VARCHAR(100) NOT NULL,
    node_id       VARCHAR(100),
    payload_json  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

-- WorkflowEventOutboxRepository's poll query.
CREATE INDEX idx_workflow_event_outbox_unpublished ON workflow_event_outbox (published_at)
    WHERE published_at IS NULL;
