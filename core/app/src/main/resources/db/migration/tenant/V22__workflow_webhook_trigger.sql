-- Workflow webhook trigger (#163) - "how do customers trigger it": a
-- per-workflow secret token that lets an external system start a run via
-- POST /webhook/workflows/{token}, without requiring the still-unbuilt
-- Kafka-consumer real-channel-trigger wiring. Nullable/false until a tenant
-- explicitly enables it from the canvas.
ALTER TABLE workflow_definition ADD COLUMN webhook_token VARCHAR(64) UNIQUE;
ALTER TABLE workflow_definition ADD COLUMN webhook_enabled BOOLEAN NOT NULL DEFAULT FALSE;
