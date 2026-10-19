-- #815 Gap 1 - deterministic tool-call/output validator (ToolCallOutputValidator).
-- Durable signal for "this tool call's output failed structural validation
-- (length bound / malformed JSON) and was not returned to the model as-is,"
-- distinct from error_message (which is set only when the tool call itself
-- threw) - a validation failure is a normal, non-throwing tool result the
-- model reacted to, same posture as an approval-gated call.
ALTER TABLE tool_call_log
    ADD COLUMN validation_failed BOOLEAN NOT NULL DEFAULT FALSE;
