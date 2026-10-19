-- Tool-calling grade of a hosted-pool row (docs/local-models.md, "Tool calls from
-- small models"). Measured when the row is saved: a canned two-tool prompt is sent
-- to the model and the reply is graded
--   STRUCTURED  the reply carried a structured tool_calls entry,
--   TEXT        the reply was a tool call written as text (TextToolCallParser),
--   NONE        the model answered in prose, or the server reports no tool support
--               (Ollama /api/show "capabilities" without "tools": graded without a call).
-- NULL = not measured (Vertex rows, a probe that could not reach the server, rows
-- saved before this column existed). The chain of enabled rows skips a NONE row
-- while any other enabled row exists; the admin page shows the grade as a badge.
ALTER TABLE control_plane.platform_hosted_llm_provider
    ADD COLUMN IF NOT EXISTS tool_call_grade VARCHAR(16);
