// Tool classification registry row/envelope (mirrors ToolClassification +
// ToolTrustTier / ToolSensitivity / ToolDeterminism in
// core/agents/.../tools/classification). Imported as a TYPE ONLY so this
// module keeps no runtime dependency on a component folder -
// toolClassification.ts pulls in lucide icons for its pill metadata, and
// `import type` is erased at compile time so none of that is bundled with the
// API client. That file is the single place the console decides what a tier
// MEANS; duplicating the shape here would let the two drift. See
// listToolClassifications below.
import type { ToolClassificationListResponse } from "../../components/tools/toolClassification";
import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type {
  SkillSummary,
  SkillRegistrySearch,
  SkillDetail,
  AgentSkills,
  PendingSkillImprovement,
  DistillPattern,
  DistillResult,
  OpenApiToolSource,
  OpenApiTool,
  OpenApiSpecPreview,
  ToolTestResult,
  CodeToolTestResult,
} from "./types";
import type { CustomCodeTool, CodeToolGenerationResponse } from "../../components/CustomCodeToolsPanel";

export const toolsApi = {
  // #424 - "user-created tools (generated scripts)", scoped to sandboxed SpEL expressions - see
  // UserToolScript's javadoc. Any authenticated tenant member can manage these, same auth posture
  // as registerMcpServer above (not admin-only).
  listUserToolScripts: () => apiFetch(`/api/tools/scripts`).then(toJson),
  createUserToolScript: (payload: object) =>
    apiFetch(`/api/tools/scripts`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  updateUserToolScript: (id: string, payload: object) =>
    apiFetch(`/api/tools/scripts/${id}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  setUserToolScriptEnabled: (id: string, enabled: boolean) =>
    apiFetch(`/api/tools/scripts/${id}/enabled`, { method: "PATCH", ...jsonBody({ enabled }) }).then(toJson),
  deleteUserToolScript: (id: string) => apiFetch(`/api/tools/scripts/${id}`, { method: "DELETE" }),
  testUserToolScript: (expression: string, sampleInputJson: string) =>
    apiFetch(`/api/tools/scripts/test`, { method: "POST", ...jsonBody({ expression, sampleInputJson }) }).then(toJson<ToolTestResult>),

  // Custom Code Tools - the sibling of the SpEL scripts above, one rung up:
  // a tenant-admin writes a whole Java or Python program that becomes an
  // agent tool, compiled/validated on save and executed server-side in an
  // isolated sandbox (no network, no filesystem, no platform data). Where
  // the SpEL surface is sandboxed BY CONSTRUCTION (a grammar with no loops
  // and no method calls), this one is sandboxed by CONTAINMENT, which is
  // why it's admin-only and why it's a separate screen (/tools/code) rather
  // than another panel on /tools.
  //
  // The list call is quiet on 400 + 403 because on this endpoint both are
  // expected STATES rather than failures, and routing them through toJson
  // would pop a global error toast on every single page load for a
  // perfectly normal situation (exactly the regression toJsonQuietOn was
  // introduced for - see its javadoc above):
  //   403 -> the signed-in user isn't a tenant-admin; the page renders a
  //          read-only explanation instead of a broken screen.
  //   400 -> "Custom code tools are not enabled on this deployment
  //          (set TOOL_SANDBOX_ENABLED=true)"; the page renders a
  //          feature-not-enabled empty state.
  // Mutations deliberately stay on plain toJson: a 403 there IS a genuine
  // "that didn't work" worth a toast, not a page-level state.
  listCustomCodeTools: () => apiFetch(`/api/tools/code`).then(toJsonQuietOn([400, 403])),
  createCustomCodeTool: (payload: object) =>
    apiFetch(`/api/tools/code`, { method: "POST", ...jsonBody(payload) }).then(toJson<CustomCodeTool>),
  updateCustomCodeTool: (id: string, payload: object) =>
    apiFetch(`/api/tools/code/${id}`, { method: "PUT", ...jsonBody(payload) }).then(toJson<CustomCodeTool>),
  setCustomCodeToolEnabled: (id: string, enabled: boolean) =>
    apiFetch(`/api/tools/code/${id}/enabled`, { method: "PATCH", ...jsonBody({ enabled }) }).then(toJson<CustomCodeTool>),
  deleteCustomCodeTool: (id: string) => apiFetch(`/api/tools/code/${id}`, { method: "DELETE" }),
  // Which vault secrets a custom code tool may receive. Names only - a value
  // never travels on this path. Separate from updateCustomCodeTool because
  // granting a program a credential is a different decision from editing its
  // code, and the backend gates this one on workspace-admin while ordinary
  // code edits are not.
  setCustomCodeToolSecrets: (id: string, allowedSecretNames: string[]) =>
    apiFetch(`/api/tools/code/${encodeURIComponent(id)}/secrets`, {
      method: "PUT",
      ...jsonBody({ allowedSecretNames }),
    }).then(toJson),
  // sampleInput is arbitrary JSON (whatever the tool's own input schema
  // describes), NOT a pre-stringified blob - the caller parses the textarea
  // and hands the real value over. CustomCodeToolTestRequest calls it `input`.
  testCustomCodeTool: (id: string, sampleInput: unknown) =>
    apiFetch(`/api/tools/code/${id}/test`, { method: "POST", ...jsonBody({ input: sampleInput }) }).then(toJson<CodeToolTestResult>),
  // Re-runs compilation/validation against the already-saved source, for a
  // REJECTED tool whose rejection was environmental (sandbox image not up
  // yet) rather than a real code error. Returns the updated tool.
  revalidateCustomCodeTool: (id: string) =>
    apiFetch(`/api/tools/code/${id}/revalidate`, { method: "POST" }).then(toJson<CustomCodeTool>),
  // "Ask AI" draft generation for a whole tool program (Java/Python) - a
  // genuine failed action (bad requirement, no active provider, sandbox
  // disabled), so plain toJson rather than toJsonQuietOn: the panel wants
  // the global toast plus the thrown message either way.
  generateCustomCodeTool: (payload: object) =>
    apiFetch(`/api/tools/code/generate`, { method: "POST", ...jsonBody(payload) }).then(toJson<CodeToolGenerationResponse>),
  // Thin, LLM-free re-validation of a source string against the sandbox -
  // used both by the generate flow's own follow-up "regenerate" affordance
  // and by the edge on-device codegen path (lib/edge/codegen.ts), which
  // calls this directly (bypassing this wrapper, since it must stay silent
  // on failure) to get the authoritative verdict on locally-produced code.
  validateCustomCodeTool: (payload: object) =>
    apiFetch(`/api/tools/code/validate`, { method: "POST", ...jsonBody(payload) }).then(toJson),

  // ── Agent Skills (core com.agenticai.core.skills) ─────────────────────────
  // A skill is a SKILL.md (name + description frontmatter, markdown body) plus
  // optional bundled files. Agents see only name + description until a request
  // matches, then load the body on demand. Built-ins ship with the platform
  // (id is null, not editable); custom ones are per workspace. Every write is
  // workspace-admin only server-side - a 403 there is a real "you can't do
  // that" and gets the normal toast.
  listSkills: (): Promise<SkillSummary[]> => apiFetch(`/api/skills`).then(toJson<SkillSummary[]>),
  getSkill: (name: string): Promise<SkillDetail> =>
    apiFetch(`/api/skills/${encodeURIComponent(name)}`).then(toJson<SkillDetail>),
  getSkillFile: async (name: string, path: string): Promise<string> => {
    const res = await apiFetch(
      `/api/skills/${encodeURIComponent(name)}/file?path=${encodeURIComponent(path)}`
    );
    if (!res.ok) await toJson(res);
    return res.text();
  },
  createSkill: (skillMd: string): Promise<SkillDetail> =>
    apiFetch(`/api/skills`, { method: "POST", ...jsonBody({ skillMd }) }).then(toJson<SkillDetail>),
  updateSkill: (id: string, skillMd: string): Promise<SkillDetail> =>
    apiFetch(`/api/skills/${encodeURIComponent(id)}`, { method: "PUT", ...jsonBody({ skillMd }) }).then(toJson<SkillDetail>),
  deleteSkill: (id: string) => apiFetch(`/api/skills/${encodeURIComponent(id)}`, { method: "DELETE" }),
  setSkillEnabled: async (name: string, enabled: boolean): Promise<void> => {
    const res = await apiFetch(`/api/skills/${encodeURIComponent(name)}/enabled`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    });
    if (!res.ok) await toJson(res);
  },
  // No Content-Type header on purpose: the browser must set the multipart
  // boundary itself.
  importSkill: (file: File, replace = false): Promise<{ skill: SkillDetail; skippedPaths: string[] }> => {
    const form = new FormData();
    form.append("file", file);
    return apiFetch(`/api/skills/import?replace=${replace ? "true" : "false"}`, {
      method: "POST",
      body: form,
    }).then(toJson<{ skill: SkillDetail; skippedPaths: string[]; }>);
  },
  searchSkillRegistry: (q: string, page = 1): Promise<SkillRegistrySearch> =>
    apiFetch(`/api/skills/registry/search?q=${encodeURIComponent(q)}&page=${page}`).then(toJson<SkillRegistrySearch>),
  // expectedSha256: the hash from the scan the admin confirmed; the server refuses (409) if the source now serves different content.
  installRegistrySkill: (
    githubUrl: string,
    replace = false,
    expectedSha256: string | null = null
  ): Promise<{ skill: SkillDetail; skippedPaths: string[] }> =>
    apiFetch(`/api/skills/registry/install`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ githubUrl, replace, expectedSha256 }),
    }).then(toJson<{ skill: SkillDetail; skippedPaths: string[]; }>),
  exportSkill: async (name: string): Promise<Blob> => {
    const res = await apiFetch(`/api/skills/${encodeURIComponent(name)}/export`);
    if (!res.ok) await toJson(res);
    return res.blob();
  },

  // ---- staged agent-authored rewrite review (V243 improve_skill / V395
  // distillation). The staged text is agent-authored content an admin must
  // read before it touches a prompt, so all three calls are workspace-admin
  // server-side; the panel only renders the affordance for that audience.
  getSkillImprovement: (id: string): Promise<PendingSkillImprovement | null> =>
    apiFetch(`/api/skills/${encodeURIComponent(id)}/improvement`).then((res) =>
      res.status === 404 ? null : toJson(res)
    ),
  applySkillImprovement: (id: string): Promise<SkillDetail> =>
    apiFetch(`/api/skills/${encodeURIComponent(id)}/improvement/apply`, { method: "POST" }).then(toJson<SkillDetail>),
  discardSkillImprovement: (id: string): Promise<void> =>
    apiFetch(`/api/skills/${encodeURIComponent(id)}/improvement`, { method: "DELETE" }).then(
      async (res) => {
        if (!res.ok) await toJson(res);
      }
    ),

  // ---- skill distillation (V395): mine THIS agent's repeated successful
  // tool-call patterns. GET is the read-only preview, POST stages each
  // pattern as a DISABLED draft skill (digest-pinned, approval-gated) - no
  // distilled skill can reach a prompt until an admin enables it.
  previewSkillDistillation: (agentId: string): Promise<DistillPattern[]> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/skills/distill`).then(toJson<DistillPattern[]>),
  distillSkills: (agentId: string): Promise<DistillResult> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/skills/distill`, { method: "POST" }).then(toJson<DistillResult>),
  getAgentSkills: (agentId: string): Promise<AgentSkills> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/skills`).then(toJson<AgentSkills>),
  updateAgentSkills: (
    agentId: string,
    payload: { skillsEnabled: boolean; allowedSkillNames: string[] }
  ): Promise<AgentSkills> =>
    apiFetch(`/api/agents/${encodeURIComponent(agentId)}/skills`, { method: "PUT", ...jsonBody(payload) }).then(toJson<AgentSkills>),

  // Import from OpenAPI - the no-code sibling of the two tenant-tool features
  // above (core/agents com.agenticai.core.tools.openapi): a tenant points the
  // platform at an OpenAPI 3 / Swagger 2 document and every operation in it
  // becomes an agent tool through the same in-process ToolCallback path as
  // user scripts and custom code tools. Reads are open to any signed-in
  // member; preview/create/refresh/update/delete are tenant-admin (the
  // backend gates them, see SecurityConfig) - a 403 on a mutation is a
  // genuine "that didn't work" and goes through plain toJson, while the
  // panel hides the write affordances for non-admins up front via useAuth.
  // The secret never comes back on any of these; `hasSecret` +
  // `secretPreview` ("...4242") is all the UI is told.
  listOpenApiToolSources: () => apiFetch(`/api/tools/openapi`).then(toJson<OpenApiToolSource[]>),
  // Parse (and, for a URL, fetch) without persisting - the operation picker.
  previewOpenApiToolSource: (payload: { name?: string; toolPrefix?: string; specUrl?: string; specText?: string }) =>
    apiFetch(`/api/tools/openapi/preview`, { method: "POST", ...jsonBody(payload) }).then(toJson<OpenApiSpecPreview>),
  createOpenApiToolSource: (payload: object) =>
    apiFetch(`/api/tools/openapi`, { method: "POST", ...jsonBody(payload) }).then(toJson<OpenApiToolSource>),
  // Settings only (name / base URL / auth); a blank `secret` keeps the stored one.
  updateOpenApiToolSource: (id: string, payload: object) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(id)}`, { method: "PUT", ...jsonBody(payload) }).then(toJson<OpenApiToolSource>),
  // Re-fetch (URL source) or re-parse (pasted source) and reconcile the tools;
  // a failed refresh is RECORDED on the source (lastError) rather than thrown.
  refreshOpenApiToolSource: (id: string) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(id)}/refresh`, { method: "POST" }).then(toJson<OpenApiToolSource>),
  setOpenApiToolSourceEnabled: (id: string, enabled: boolean) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(id)}/enabled`, { method: "PATCH", ...jsonBody({ enabled }) }).then(toJson<OpenApiToolSource>),
  deleteOpenApiToolSource: (id: string) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(id)}`, { method: "DELETE" }),
  listOpenApiTools: (sourceId: string) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(sourceId)}/tools`).then(toJson<OpenApiTool[]>),
  setOpenApiToolEnabled: (sourceId: string, toolId: string, enabled: boolean) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(sourceId)}/tools/${encodeURIComponent(toolId)}/enabled`, {
      method: "PATCH",
      ...jsonBody({ enabled }),
    }).then(toJson<OpenApiTool>),
  // Dry-run against the real API; 200 with { resultJson } whatever the API
  // said (a 404 from the API is a normal result of trying a tool out).
  testOpenApiTool: (sourceId: string, toolId: string, inputJson: string) =>
    apiFetch(`/api/tools/openapi/${encodeURIComponent(sourceId)}/tools/${encodeURIComponent(toolId)}/test`, {
      method: "POST",
      ...jsonBody({ inputJson }),
    }).then(toJson<ToolTestResult>),

  // Tool classification registry (trust x sensitivity x determinism) - the
  // read side of core/agents' ToolClassificationRegistry, which fails CLOSED:
  // a tool whose three axes are not all declared is refused at call time, not
  // waved through. /tools' ToolClassificationPanel renders that registry so a
  // tenant-admin can see which of their tools are blocked and why, instead of
  // discovering it as a tool call that mysteriously never runs.
  //
  // 404 -> { unsupported: true } rather than a throw, the same convention
  // testMcpServer/acknowledgeMcpServer above already use: at the time of
  // writing the registry has a service and entities in core but NO REST
  // controller (verified by grep - there is no @RestController under
  // core/agents/.../tools/), so every deployment answers 404 today. The panel
  // renders a distinct "not available on this deployment yet" state for that,
  // which must never be conflated with an error (something is broken) or with
  // an empty list (nothing needs classifying - the dangerous misreading, since
  // an unclassified tool is a BLOCKED tool). No global toast either: a core
  // that predates a feature is not a failure worth alarming the user about.
  //
  // The path and the response envelope are still a PROPOSAL, not an agreed
  // contract - only the row's three axis enums and its toolName/requiredScope/
  // irreversible/needsToolContext fields are transcribed from the shipped Java
  // record. If core mounts the read elsewhere, this one line changes.
  listToolClassifications: async (): Promise<ToolClassificationListResponse> => {
    const res = await apiFetch(`/api/tools/classifications`);
    if (res.status === 404) return { unsupported: true };
    return toJson(res);
  },
};
