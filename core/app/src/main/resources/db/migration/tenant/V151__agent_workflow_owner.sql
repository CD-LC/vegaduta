-- Cross-user resource sharing retrofit (Round 2) - the next-workstream half
-- V147__resource_sharing.sql's own header comment flagged: gives `agent` and
-- `workflow_definition` a `created_by` column so ResourceAccessService#requireAccess
-- (core/sharing) has something real to check instead of every resource forever
-- resolving to createdBy == null.
--
-- Version V151: application.yml's flyway.locations merges controlplane/ and
-- tenant/ into ONE version stream (see V147's own note for the same rule) -
-- highest version present in either folder as of this writing is V150
-- (tenant/V150__agent_text_model.sql; controlplane's highest is V147). V151 is
-- the next free slot in the combined stream.
--
-- Rollout is PRIVATE-GOING-FORWARD-ONLY, no backfill, by deliberate design
-- (not an oversight): ALTER TABLE ... ADD COLUMN with no DEFAULT leaves every
-- EXISTING row's new column NULL - this is standard, well-defined Postgres
-- behavior, not something this migration has to do anything special to get.
-- ResourceAccessService#requireAccess treats createdBy == null as
-- AccessCheckResult.LEGACY_OPEN - always allowed, regardless of the required
-- permission level - so every agent/workflow that exists before this
-- migration runs keeps behaving EXACTLY as it does today (tenant-wide-open to
-- any authenticated tenant member) forever, not just until some future
-- backfill job runs. Only agents/workflows CREATED after this migration (via
-- AgentService#create/WorkflowService#create, which now populate this column
-- from the creating user's TenantContext subject) become private-by-default
-- and subject to the new RUNNER/VIEWER/EDITOR grant model. This is the exact
-- same "nullable, no backfill, no breaking change" shape V147's own resource_grant
-- table already established for the grant side of this feature.
--
-- No FK to a `resource_grant`-style table needed here (this is the OWNER
-- column, not a grant) and no index: every read of this column at
-- runtime is "I already loaded this one specific Agent/WorkflowDefinition
-- row for some other reason (get/update/chat/run), what's its createdBy" -
-- a plain column read off an already-fetched row, not a query predicate.
-- ResourceOwnerLookup implementations (AgentResourceOwnerLookup/
-- WorkflowResourceOwnerLookup) do a findById() lookup, which is already
-- indexed via the primary key.
ALTER TABLE agent
    ADD COLUMN created_by VARCHAR(255);

ALTER TABLE workflow_definition
    ADD COLUMN created_by VARCHAR(255);
