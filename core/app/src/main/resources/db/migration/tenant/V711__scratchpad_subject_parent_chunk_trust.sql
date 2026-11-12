-- Phase 0 residuals G5 (scratchpad subject scope) and G7 (parent-chunk trust), companion to V676-V678.
--
-- G5. agent_scratchpad rows had no owner: a note written with scope "agent" (session_id NULL) was readable
-- by every user of the agent, and one written on a tainted turn could be planted for all of them.
-- `subject_key` is the same key agent_memory.subject_key carries (the Keycloak principal name or the
-- channel external id), written from the caller of the turn. The model's read/list/delete paths only see
-- an agent-pad note whose subject_key equals the caller's.
--
-- Existing rows: their author is unknown, so subject_key stays NULL and nothing can be backfilled. Decision
-- (safest): a NULL-subject AGENT-pad note (session_id IS NULL) is no longer returned to any model call; it
-- is kept (not deleted) and stays visible to operators on GET /api/agents/{id}/scratchpad, who can
-- re-create or discard it. Session-pad notes with a NULL subject stay readable inside their session: the
-- session id is already derived per caller, and a later write by the caller claims the row.
--
-- Uniqueness: an agent-pad note is unique per (agent, subject, key); a session-pad note per
-- (agent, session, key) as before (the subject component collapses to '' there).
ALTER TABLE agent_scratchpad ADD COLUMN subject_key TEXT;

DROP INDEX IF EXISTS ux_agent_scratchpad_scope_key;
CREATE UNIQUE INDEX ux_agent_scratchpad_scope_key
    ON agent_scratchpad (
        agent_id,
        (COALESCE(session_id, '00000000-0000-0000-0000-000000000000'::uuid)),
        (CASE WHEN session_id IS NULL THEN COALESCE(subject_key, '') ELSE '' END),
        key);

-- Erasure by subject and the per-subject pad listing.
CREATE INDEX idx_agent_scratchpad_subject ON agent_scratchpad (subject_key) WHERE subject_key IS NOT NULL;

-- G7. A parent chunk (V432) is the larger passage handed to the model next to a retrieved child. It was
-- written even when its children are QUARANTINED (V677) and carried no trust of its own. It now carries the
-- trust of the children it was written with, and the parent read path only returns TRUSTED parents.
--
-- Backfill: a parent referenced by at least one QUARANTINED child chunk becomes QUARANTINED (with that
-- child's source_taint); everything else keeps the TRUSTED default. Children link to parents through
-- knowledge_chunks.metadata->>'parent_id'.
ALTER TABLE knowledge_parent_chunk
    ADD COLUMN trust        VARCHAR(16) NOT NULL DEFAULT 'TRUSTED',
    ADD COLUMN source_taint TEXT;

ALTER TABLE knowledge_parent_chunk
    ADD CONSTRAINT knowledge_parent_chunk_trust_chk CHECK (trust IN ('TRUSTED', 'QUARANTINED'));

UPDATE knowledge_parent_chunk p
   SET trust = 'QUARANTINED',
       source_taint = q.source_taint
  FROM (SELECT DISTINCT ON (c.metadata->>'parent_id') c.metadata->>'parent_id' AS parent_id, c.source_taint
          FROM knowledge_chunks c
         WHERE c.trust = 'QUARANTINED' AND c.metadata ? 'parent_id') q
 WHERE p.id::text = q.parent_id;

CREATE INDEX idx_knowledge_parent_chunk_quarantined ON knowledge_parent_chunk (collection_id) WHERE trust = 'QUARANTINED';
