// FREE-tier resource caps, in ONE place.
//
// core/tenancy seeds three plan_limits rows (FREE/PREMIUM/UNLIMITED, see
// V101__plan_limits.sql) with different maxAgents/maxWorkflows per plan, so
// these are NOT universal - AgentWorkbench.tsx/WorkflowDesignerPanel.tsx must
// derive the tenant's actual cap from getTenantSettings()'s live
// maxAgents/maxWorkflows fields and use these constants only as the
// FREE-tier default/loading-state fallback, never as a blanket gate. What
// lives here is a UX head start (disable the button, explain why, offer the
// upgrade) and never the real gate. They're also written to match what the
// 403 response body's own message says, so a bypass attempt (e.g. two tabs
// open at once) can never contradict what the UI already told the person.
//
// History: AGENT_LIMIT used to exist twice - once in the pre-AXON
// AgentsPanel.tsx and again, copy-pasted, in the AgentWorkbench that replaced
// it as the routed /agents screen. The copy was justified by this project's
// standing "don't delete superseded work" posture (importing from a file that
// was notionally on its way out felt wrong), but that reasoning inverted the
// cost: two literals that must agree, with nothing making them agree. The
// dead panel is gone (2026-07-29 dead-code pass; git history has it) and the
// constant now has a single home that both live screens import from.
//
// Note for anyone changing a number here: web/e2e/specs/18-subscription-tiers
// .spec.js deliberately keeps its OWN copy of AGENT_LIMIT. That is not an
// oversight to "fix" by importing this module - a test that derived its
// expectation from the code under test would pass no matter what the code
// said. Update it by hand, on purpose, when a cap really moves.

/** Agents a FREE-tier tenant may create (TenantLimits#maxAgents); fallback only. */
export const AGENT_LIMIT = 5;

/** Workflows a FREE-tier tenant may create (TenantLimits#maxWorkflows); fallback only. */
export const WORKFLOW_LIMIT = 3;
