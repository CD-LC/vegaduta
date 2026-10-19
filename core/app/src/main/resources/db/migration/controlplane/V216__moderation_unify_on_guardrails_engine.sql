-- Moderation subsystem unification: retire the orphaned control-plane
-- persistence layer V206 created, and leave ONE moderation engine.
--
-- gated:contract
--
-- WHY. The platform shipped two complete, mutually-unaware content-moderation
-- subsystems. core/guardrails/moderation is the live one: AgentChatService
-- calls it on every inbound turn, DemoShareController and DemoMagicController
-- call it too, it reads its config from the generic platform_setting store,
-- and it writes its human-review queue into each tenant's own schema
-- (tenant V121). core/platform/moderation was an admin CRUD layer built
-- against a second, private copy of the same concepts; its only intended
-- consumer, web-admin's /safety page, is still a placeholder that renders the
-- endpoint strings as literal text and never calls them. The two even shared
-- three class names, which is what crashed startup on 2026-08-17 and was
-- papered over in 17621547 with explicit bean/entity names rather than
-- resolved. The admin API survives and is rewired onto the live engine's
-- storage; the duplicate tables go.
--
-- WHY A NEW MIGRATION. V206 is applied in staging AND production. Editing or
-- renumbering an applied migration is forbidden (scripts/ci/flyway-lint.sh
-- check (a); the 2026-07-31 duplicate-V156 outage). Removal is only ever a
-- forward migration.
--
-- VERSION 216. controlplane/ and tenant/ share ONE Flyway version namespace
-- (one spring.flyway.locations, one ordered run). At the time of writing
-- controlplane reaches V206 and tenant reaches V215 (with V210-V212 present
-- as another agent's in-flight working-tree files). 213/214 are gaps, but a
-- gap in a shared namespace usually means someone's unlanded branch is
-- holding it - so this takes 216, the first number strictly above EVERYTHING
-- in use across both folders. Same reasoning check (c) enforces mechanically.

-- ---------------------------------------------------------------------------
-- 1. Carry any operator decisions over before dropping the table that holds
--    them.
--
--    This table is almost certainly empty: its only writer is
--    PUT /api/platform/moderation/policy-packs, whose only intended caller is
--    a coming-soon placeholder. "Almost certainly" is not a thing to bet an
--    operator's safety configuration on, so the rows are translated into the
--    live engine's key scheme first.
--
--    The two vocabularies do not map 1:1. Three of the old ids are the same
--    category under a different name and are rewritten; csam is skipped
--    because the engine force-disables it and the API refuses to enable it,
--    so a carried-over row could never be read by anything.
-- ---------------------------------------------------------------------------
WITH mapped AS (
    SELECT
        'moderation.pack.'
          || CASE pack_id
               WHEN 'profanity'  THEN 'foul_language'
               WHEN 'restricted' THEN 'restricted_content'
               WHEN 'terrorism'  THEN 'extremism'
               ELSE pack_id
             END
          -- Scope suffix must match ModerationPolicyService.resolveEnabled
          -- exactly: a tenant row wins over a plan row, and plan keys are
          -- upper-cased because that is how the plan token (FREE/PREMIUM/...)
          -- reaches the resolver from TenantPlan.name().
          || CASE scope_type
               WHEN 'TENANT' THEN '.enabled.tenant.' || scope_key
               ELSE               '.enabled.plan.'   || upper(scope_key)
             END                                          AS key,
        CASE WHEN enabled THEN 'true' ELSE 'false' END     AS value,
        updated_by,
        updated_at
    FROM control_plane.moderation_policy_pack_setting
    WHERE pack_id <> 'csam'
)
INSERT INTO control_plane.platform_setting (key, value, description, updated_by, updated_at)
SELECT
    key,
    value,
    'Migrated from control_plane.moderation_policy_pack_setting (V206) by V216.',
    updated_by,
    COALESCE(updated_at, now())
FROM mapped
-- platform_setting.key is VARCHAR(120). A pathologically long tenant id would
-- otherwise abort the whole migration for one row; skipping is the right
-- failure mode here, because the pack stays at its default-off resolution
-- rather than the deploy failing. In practice no tenant id comes close.
WHERE length(key) <= 120
-- An operator who has already set the equivalent key by hand wins - this is a
-- best-effort carry-over, not an authority.
ON CONFLICT (key) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Drop the duplicate policy-pack store.
--
--    Superseded by the moderation.pack.<configKey>.* keys in platform_setting,
--    which the running engine already reads on every turn. Keeping this table
--    would preserve the exact defect being fixed: an admin surface writing
--    configuration that the enforcement path never consults.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS control_plane.moderation_policy_pack_setting;

-- ---------------------------------------------------------------------------
-- 3. Drop the duplicate review queue.
--
--    Unreachable by construction: nothing outside its own package ever called
--    the service that inserts into it, so it has always been empty and always
--    would have been. The real queue is the per-tenant moderation_review_item
--    created by tenant V121 and written by ModerationService whenever a
--    signal lands in the review band. The platform-admin surface now reads
--    that queue by fanning out across tenant schemas, which is the honest
--    shape of a cross-tenant view over schema-isolated data.
--
--    Note this DROP names the control_plane copy explicitly. The tenant-schema
--    table of the same name is a DIFFERENT table and must survive - it holds
--    live flagged content.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS control_plane.moderation_review_item;

-- ---------------------------------------------------------------------------
-- 4. Remove the four orphaned threshold rows V206 seeded.
--
--    Same class of defect as the table above, and it was not in the original
--    audit. These were written and read only by ModerationThresholdService,
--    as four platform-wide 0.0-1.0 scalars. The engine bands against per-pack
--    integers instead (moderation.pack.<key>.threshold.review|block, 0-100,
--    defaulting 50/90), so an operator tuning these four moved nothing. The
--    thresholds endpoint survives and is rewired onto the per-pack keys; these
--    rows are deleted so they cannot mislead anyone browsing platform_setting.
--
--    A data DELETE, not DDL - no gated:contract concern, and nothing has a
--    foreign key to platform_setting.
-- ---------------------------------------------------------------------------
DELETE FROM control_plane.platform_setting
WHERE key IN (
    'platform.moderation.threshold.semantic',
    'platform.moderation.threshold.external',
    'platform.moderation.review-band-low',
    'platform.moderation.review-band-high'
);

-- ---------------------------------------------------------------------------
-- 5. Master switch stays absent, deliberately.
--
--    No row is seeded for moderation.enabled. ModerationPolicyService defaults
--    it to false, which makes ModerationService return ALLOW and write nothing
--    at all. Unifying two subsystems must not be the change that switches
--    content moderation on across every tenant - turning it on stays a
--    separate, explicit operator decision.
-- ---------------------------------------------------------------------------
