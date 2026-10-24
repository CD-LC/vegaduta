-- Folder: controlplane (it edits control_plane.community_template).
--
-- V502 seeded the "Personal Assistant" starter template with "reasoningDepth": "BALANCED". That is
-- not a ReasoningDepth value (FAST, CAREFUL, RIGOROUS, PLANNED). Importing it used to degrade the
-- invalid value to "unset", i.e. FAST, without telling anyone; the importer now rejects unknown
-- enum values, so the stored row must say what it always effectively meant.
--
-- FAST is chosen because that is what every import has produced so far and because the template is
-- built for small local models, where the extra self-check model calls of CAREFUL cost more than
-- they give. V502 itself is a shipped migration and is never edited.
--
-- Idempotent and additive: only a row that still carries the invalid value is touched.
UPDATE control_plane.community_template
   SET config_json = regexp_replace(
           config_json, '"reasoningDepth"\s*:\s*"BALANCED"', '"reasoningDepth": "FAST"')
 WHERE kind = 'AGENT'
   AND config_json ~ '"reasoningDepth"\s*:\s*"BALANCED"';
