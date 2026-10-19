-- Premium KB collections cap -> unlimited (docs/PRICING-MONETIZATION-AUDIT-2026-07-31.md
-- §2.5 / §6 item 3 / approval item A2): the public pricing page promises Premium
-- "unlimited" knowledge-base collections, but V101 seeded max_kb_collections = 5
-- for PREMIUM - code was quietly under-delivering on a stated promise. -1 is the
-- established "no cap" sentinel (see V101's header), translated to
-- Optional.empty() by TenantLimitsServiceImpl#maxKbCollections. The real
-- cost lever is embedding volume (sidecar usage caps own that later), so
-- collections themselves are safe to uncap.
--
-- OPERATIONAL NOTE: PlanLimitsService caches plan_limits rows in Redis under
-- plan-limits:{PLAN} with NO TTL (evicted only by the admin PUT endpoint's
-- evictPlanLimitsCache call). A raw SQL migration does not evict that key, so
-- after deploy run:  redis-cli DEL plan-limits:PREMIUM   (or re-save the PREMIUM
-- row once from the web-admin plan-limits editor) or Premium tenants keep
-- seeing the old cap of 5 until the key is dropped.
--
-- Version 158: shared controlplane/tenant Flyway sequence; 156 was the highest
-- across both folders and 157 is claimed by tenant_event_trial in this same
-- change-set. Orchestrator: re-verify numbering against other agents' work
-- before commit (see V157's header note).
UPDATE control_plane.plan_limits
SET max_kb_collections = -1
WHERE plan = 'PREMIUM';
