import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { ShareStatusResponse } from "./types";
import { pagedQuery, toPaged, type PagedRows } from "./paged";

export const sharingApi = {
  // Agent sharing to the external world - owner side (mint/inspect/revoke a
  // public share link for one of this tenant's own agents). The PUBLIC half
  // (what an anonymous viewer with the link can do) is unauthenticated and
  // deliberately NOT called through this apiFetch-based client at all - see
  // web/app/agent/[shareId]/api.ts, the same separation /a/[shareId]/api.ts
  // already established for the demo-sandbox share surface.
  getAgentShareStatus: (agentId: string) => apiFetch(`/api/agents/${agentId}/share`).then(toJson<ShareStatusResponse>),
  createAgentShare: (agentId: string) => apiFetch(`/api/agents/${agentId}/share`, { method: "POST" }).then(toJson<ShareStatusResponse>),
  // Revoke answers 204 with no body, so toJson resolves null.
  revokeAgentShare: (agentId: string) => apiFetch(`/api/agents/${agentId}/share`, { method: "DELETE" }).then(toJson<void>),

  // Workflow counterpart of the agent-share trio right above - same
  // owner-authenticated public-link endpoint shape (WorkflowShareOwnerController),
  // just mounted at /api/workflows/{workflowId}/share instead of /api/agents/{agentId}/share.
  getWorkflowShareStatus: (workflowId: string) => apiFetch(`/api/workflows/${workflowId}/share`).then(toJson<ShareStatusResponse>),
  createWorkflowShare: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/share`, { method: "POST" }).then(toJson<ShareStatusResponse>),
  revokeWorkflowShare: (workflowId: string) =>
    apiFetch(`/api/workflows/${workflowId}/share`, { method: "DELETE" }).then(toJson<void>),

  // Cross-user resource sharing (core/sharing's ShareController/
  // SharedWithMeController) - named-teammate grants on a single Agent or
  // Workflow, generalized across both resource types via the same
  // /api/{resourceKind}/{resourceId}/grants path the backend mounts at
  // (`resourceKind` is the literal "agents"/"workflows" path segment, not
  // the ResourceType enum name). Distinct from getAgentShareStatus/
  // createAgentShare/revokeAgentShare above (anonymous public link, one
  // binary state) - this is named internal teammates with three graded
  // permission levels (RUNNER/VIEWER/EDITOR).
  //
  // listResourceGrants goes through toJsonQuietOn([404]) rather than plain
  // toJson on purpose: denial here is always a 404, never a 403
  // (anti-enumeration - see ShareController's javadoc), and TeammateAccessPanel
  // is mounted on EVERY agent detail view, so a non-owner opening any agent
  // they don't own would otherwise trigger the global error toast for
  // something that is an expected state, not a failure. Same reasoning as the
  // FREE-plan 403 on the LLM-usage endpoints. The error still throws with
  // .status intact, which is how the panel decides to render nothing.
  listResourceGrants: (resourceKind: string, resourceId: string) =>
    apiFetch(`/api/${resourceKind}/${resourceId}/grants`).then(toJsonQuietOn([404])),
  grantResourceAccess: (resourceKind: string, resourceId: string, granteeEmail: string, permissionLevel: string) =>
    apiFetch(`/api/${resourceKind}/${resourceId}/grants`, {
      method: "POST",
      ...jsonBody({ granteeEmail, permissionLevel }),
    }).then(toJson<{ id: string; granteeUserId: string; granteeEmail: string | null; permissionLevel: string; [key: string]: unknown }>),
  revokeResourceAccess: (resourceKind: string, resourceId: string, grantId: string) =>
    apiFetch(`/api/${resourceKind}/${resourceId}/grants/${grantId}`, { method: "DELETE" }).then(toJson),
  // Current user's own active grants across both resource types - powers
  // /shared-with-me. No resourceKind/resourceId - this is the one endpoint
  // in this group that isn't resource-scoped.
  getSharedWithMe: () => apiFetch(`/api/shared-with-me`).then(toJson<unknown[]>),
  // Same list, one server page at a time (limit/offset + X-Total-Count / X-Has-More, see
  // docs/api/LIST-ENDPOINT-PAGING.md). A core that does not page yet ignores the params and
  // answers the whole array with no X-Total-Count; callers treat that as "everything loaded".
  getSharedWithMePage: (offset: number, limit: number): Promise<PagedRows> =>
    apiFetch(`/api/shared-with-me?${pagedQuery(new URLSearchParams(), offset, limit)}`).then((res) => toPaged(res, offset, limit)),
};
