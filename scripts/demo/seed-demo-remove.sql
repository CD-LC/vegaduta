-- Removes everything scripts/demo/seed-demo.sql wrote (psql -v schema=<tenant schema>). Refuses a tenant that was not
-- seeded. Only rows the seed created are touched: agents named "Example - ..." that own seeded "demo:..." conversations,
-- those conversations and their calls, the example price, and the demo marker.
SET LOCAL search_path TO :"schema", public;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM demo_dataset) THEN
    RAISE EXCEPTION 'tenant schema % was not seeded by seed-demo; nothing removed', current_schema();
  END IF;
END $$;

DELETE FROM agent WHERE name LIKE 'Example - %'
  AND id IN (SELECT DISTINCT agent_id FROM resolution_event WHERE conversation_key LIKE 'demo:%');
DELETE FROM conversation_correction WHERE conversation_key LIKE 'demo:%';
DELETE FROM resolution_event WHERE conversation_key LIKE 'demo:%';
DELETE FROM llm_call_log WHERE session_id LIKE 'demo:%';
DELETE FROM llm_price WHERE model_prefix = 'claude-example' AND set_by = 'seed-demo';
DELETE FROM demo_dataset;
