-- Durable per-sandbox chat transcript (core/tenancy/demo).
--
-- Version note: this repo's Flyway locations merge controlplane/ and tenant/
-- into ONE shared version sequence (see application.yml's flyway comment).
-- V145 is the highest taken across BOTH controlplane/ and tenant/ (they share one
-- sequence - V143/V144/V145 live in tenant/), so this is V146. A first cut
-- of this file was numbered V143 and collided with tenant/V143 at boot -
-- always check BOTH directories.
--
-- ---------------------------------------------------------------------------
-- WHY THE TRANSCRIPT NEEDS A TABLE AT ALL
-- ---------------------------------------------------------------------------
-- The demo sandbox's lifecycle is splitting into two phases (2026-07-28
-- product decision): a sandbox is EDITABLE (chat, images, video) for
-- `demo.editable-hours` (default 24h) after creation, then stays VIEWABLE
-- read-only until its normal `expires_at` (activity extends that out to
-- `demo.lifetime-days`, default 5 days). "Viewable" has to mean the visitor
-- can reopen /try days later and SEE the conversation they had - but until
-- now that transcript existed only in the browser tab's React state and in
-- the short-TTL Redis session memory (SessionMemoryService), both of which
-- are gone long before day 5. This table is the durable copy the read-only
-- phase renders from.
--
-- Bounded by construction: the per-session message cap (demo.message-limit,
-- default 20 turns) means at most ~40 rows per sandbox (one IN + one OUT per
-- turn), so "grows forever" is capped by the same control that already
-- bounds LLM spend.

CREATE TABLE control_plane.demo_chat_message (
    id            UUID         PRIMARY KEY,
    -- Not an FK, matching demo_generated_image's deliberate no-FK stance: a
    -- future operator-run purge of old demo sessions must not fail on a
    -- NO ACTION constraint.
    session_id    UUID         NOT NULL,
    -- 'IN' (visitor) or 'OUT' (agent reply, including labelled fallbacks -
    -- the transcript stores what the visitor actually saw).
    direction     VARCHAR(3)   NOT NULL,
    -- True when the OUT row was a canned `simulated` fallback rather than
    -- model output, so the read-only replay can keep the same honest "sample
    -- response" label the live chat showed. Always false for IN rows.
    simulated     BOOLEAN      NOT NULL DEFAULT FALSE,
    content       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- The one read that matters: "this sandbox's transcript, in order".
CREATE INDEX idx_demo_chat_message_session ON control_plane.demo_chat_message (session_id, created_at);
