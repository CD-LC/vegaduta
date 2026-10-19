-- #974 - "give a link for ticket creation and categories on which issue
-- falls under" - self-service tenant support tickets are just another row
-- in platform_issue (source = TENANT_REPORTED), reusing the existing
-- admin Issues portal end to end instead of a parallel "tickets" table.
-- This column carries the tenant's chosen category (BILLING/TECHNICAL/
-- ACCOUNT/FEATURE_REQUEST/OTHER) and stays NULL for every system-detected
-- source (EXCEPTION, WORKFLOW_RUN, TOOL_CALL, LLM_QUALITY, EVENT_OUTBOX,
-- PROVIDER_FALLBACK) - those aren't naturally categorizable the way a
-- human-filed ticket is.
--
-- Next number after controlplane/V85 (this repo's tenant and controlplane
-- migration folders share one version-number space - see V70's own header
-- comment and tasks #324/#762 for the collision history that established
-- this convention).

ALTER TABLE platform_issue ADD COLUMN category VARCHAR(32);
