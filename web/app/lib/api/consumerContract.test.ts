// @vitest-environment node
// Web side of the consumer-driven contracts (ADR-0025, docs/architecture/ADR-0025-consumer-driven-contracts.md).
// contracts/consumers/web/*.contract.json lists every core operation the console calls; core's
// ConsumerContractVerificationTest fails when the provider stops serving one. This suite keeps the
// list honest from the web side: a call site that is not in the contract, a contract entry no code
// calls any more, or a change in the number of call sites the extractor cannot read all fail here.
// Fix: run `node scripts/api-contract.mjs --write` from web/ and commit contracts/consumers/web.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { describe, expect, it } from "vitest";
import { extractCalls, extractFromSource } from "../../../scripts/api-contract/calls.mjs";
import { normalizeTemplate, pathMatches, readContractInteractions } from "../../../scripts/api-contract/extract.mjs";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const contractDir = path.resolve(webRoot, "../contracts/consumers/web");
const MANUAL = "manual.contract.json";
// Call sites core serves only on an older mount: the OpenAPI document declares /api/mcp/registrations/{id}/test and
// /acknowledge, and mcp.ts probes that mount first, then falls back to /api/mcp/registry/{id}/... for a core that
// predates it. The fallback route does not exist on this core, so it cannot be a contract interaction.
// Kept sorted: the assertion compares against a sorted list. Entries past the
// first two are routes core serves but docs/api/openapi.json does not declare
// until the spec is regenerated (OpenApiSpecGeneratorTest -Dopenapi.write=true)
// - the extractor can only contract routes the spec declares. Remove each entry
// as a regen moves it into the contract files.
const KNOWN_UNMATCHED = [
  "DELETE /api/desktop/recipes/{} app/lib/api/desktopRecipes.ts",
  "GET /api/desktop/monitor/frame app/lib/api/desktopRecipes.ts",
  "GET /api/desktop/recipes app/lib/api/desktopRecipes.ts",
  "PATCH /api/desktop/recipes/{} app/lib/api/desktopRecipes.ts",
  "POST /api/desktop/recipes app/lib/api/desktopRecipes.ts",
  "POST /api/desktop/recipes/from-run app/lib/api/desktopRecipes.ts",
  "POST /api/desktop/recipes/{}/run app/lib/api/desktopRecipes.ts",
  "POST /api/mcp/registry/{}/acknowledge app/lib/api/mcp.ts",
  "POST /api/mcp/registry/{}/test app/lib/api/mcp.ts",
];
const REGENERATE = "run `node scripts/api-contract.mjs --write` from web/ and commit contracts/consumers/web";

type Interaction = {
  id: string;
  method: string;
  path: string;
  status: number;
  file: string;
  request?: { requiredFields: string[] };
  response?: { requiredFields: string[] };
};
type Uncontracted = {
  dynamicCallSites: Record<string, number>;
  unmatched: { method: string; path: string; file: string }[];
};

const interactions = readContractInteractions(contractDir) as Interaction[];
const uncontracted = JSON.parse(fs.readFileSync(path.join(contractDir, "uncontracted.json"), "utf8")) as Uncontracted;
const calls = extractCalls(webRoot);

describe("web consumer contract", () => {
  it("has well-formed contract files", () => {
    const problems: string[] = [];
    const ids = new Set<string>();
    for (const name of fs.readdirSync(contractDir).filter((n) => n.endsWith(".contract.json"))) {
      const doc = JSON.parse(fs.readFileSync(path.join(contractDir, name), "utf8"));
      if (doc.consumer !== "web" || doc.provider !== "core")
        problems.push(`${name}: consumer/provider must be web/core`);
    }
    for (const it of interactions) {
      if (ids.has(it.id)) problems.push(`${it.file}: duplicate id ${it.id}`);
      ids.add(it.id);
      const fields = [...(it.request?.requiredFields ?? []), ...(it.response?.requiredFields ?? [])];
      for (const f of fields)
        if (!/^\$(\.[A-Za-z0-9_$@-]+|\[\*\])*$/.test(f)) problems.push(`${it.id}: bad field ${f}`);
      if (!/^\//.test(it.path) || !Number.isInteger(it.status)) problems.push(`${it.id}: bad path or status`);
    }
    expect(problems).toEqual([]);
    expect(interactions.length).toBeGreaterThan(500);
  });

  it("covers every core call the console makes", () => {
    const missing: string[] = [];
    for (const call of calls) {
      if (call.dynamic || call.methodDynamic || !call.paths) continue;
      const covered = call.paths.some(
        (p: string) =>
          interactions.some((it) => it.method === call.method && pathMatches(p, it.path)) ||
          uncontracted.unmatched.some((u) => u.method === call.method && u.path === p && u.file === call.file)
      );
      if (!covered) missing.push(`${call.method} ${call.paths.join(" | ")} (${call.file}:${call.line})`);
    }
    expect(missing, `call sites missing from contracts/consumers/web; ${REGENERATE}`).toEqual([]);
  });

  it("lists no interaction that the console no longer calls", () => {
    const stale = interactions.filter(
      (it) =>
        it.file !== MANUAL &&
        !calls.some(
          (call) => call.method === it.method && (call.paths ?? []).some((p: string) => pathMatches(p, it.path))
        )
    );
    expect(
      stale.map((it) => `${it.file} [${it.id}]`),
      `contract entries with no call site; ${REGENERATE}`
    ).toEqual([]);
  });

  it("records every call site the extractor cannot read", () => {
    const dynamic: Record<string, number> = {};
    for (const call of calls)
      if (call.dynamic || call.methodDynamic) dynamic[call.file] = (dynamic[call.file] ?? 0) + 1;
    expect(dynamic, `non-literal URLs changed; prefer a literal path, or ${REGENERATE}`).toEqual(
      uncontracted.dynamicCallSites
    );
  });

  // ADR-0025: wrapper functions are followed, so every call site is readable. This only ever shrinks: a new
  // helper that builds its URL from a runtime string must pass a literal at its callers, or be a hand-written
  // interaction in manual.contract.json with the reason; do not raise the ceiling.
  it("has no call site the extractor cannot read", () => {
    expect(uncontracted.dynamicCallSites).toEqual({});
  });

  it("allows only the documented unmatched routes", () => {
    expect(uncontracted.unmatched.map((u) => `${u.method} ${u.path} ${u.file}`).sort()).toEqual(KNOWN_UNMATCHED);
  });
});

describe("api-contract extractor", () => {
  const extract = (source: string) =>
    extractFromSource(ts.createSourceFile("x.ts", source, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS), "x.ts");

  it("reads literal, constant, builder and conditional paths", () => {
    const out = extract(`
      const BASE = "/api/tenant/roi";
      const base = (id: string) => \`/api/workflows/\${encodeURIComponent(id)}/versions\`;
      apiFetch(\`/api/agents/\${agentId}/model\`, { method: "PATCH", ...jsonBody({ providerName, model: m }) });
      apiFetch(\`\${BASE}/agents\${roiQuery(range)}\`);
      apiFetch(\`\${base(id)}/rollback\`, { method: "POST" });
      fetch(\`\${API_BASE}/api/share/\${wf ? "workflows" : "agents"}/\${id}/run?x=1\`, { method: "POST", body: JSON.stringify({ message }) });
      fetch("/api/client-log", { method: "POST" });
      apiFetch(path);
    `);
    expect(
      out.map((c: { method: string; paths?: string[]; dynamic?: boolean }) => [c.method, c.paths, !!c.dynamic])
    ).toEqual([
      ["PATCH", ["/api/agents/{}/model"], false],
      ["GET", ["/api/tenant/roi/agents"], false],
      ["POST", ["/api/workflows/{}/versions/rollback"], false],
      ["POST", ["/api/share/workflows/{}/run", "/api/share/agents/{}/run"], false],
      ["?", undefined, true],
    ]);
    expect(out[0].requestFields).toEqual(["providerName", "model"]);
    expect(out[3].requestFields).toEqual(["message"]);
  });

  it("treats a glued non-query expression as dynamic", () => {
    const out = extract("apiFetch(`/api/demo/studio${path}`);");
    expect(out[0].dynamic).toBe(true);
  });

  it("follows a wrapper that forwards a path parameter, with its prefix, method and options", () => {
    const out = extract(`
      async function call(path: string, init: RequestInit = {}) {
        return fetch(\`\${API_BASE}/api/demo/studio\${path}\`, { ...init, headers: {} });
      }
      async function openStream(path: string, body: unknown) {
        return fetch(\`\${API_BASE}/api/demo/studio\${path}\`, { method: "POST", body: JSON.stringify(body) });
      }
      call("/agents/" + id, { method: "DELETE" });
      call("/status");
      openStream(\`/runs/\${runId}/decision\`, { approved: true });
    `);
    expect(
      out.map((c: { method: string; paths?: string[]; dynamic?: boolean }) => [c.method, c.paths, !!c.dynamic])
    ).toEqual([
      ["DELETE", ["/api/demo/studio/agents/{}"], false],
      ["GET", ["/api/demo/studio/status"], false],
      ["POST", ["/api/demo/studio/runs/{}/decision"], false],
    ]);
  });

  it("binds a wrapper's method parameter and a body === undefined conditional to the caller's arguments", () => {
    const out = extract(`
      async function api(slug: string, path: string, body?: unknown) {
        return fetch(\`\${API_BASE}/api/public/apps/\${encodeURIComponent(slug)}\${path}\`, {
          method: body === undefined ? "GET" : "POST",
        });
      }
      const channelCall = (path: string, method: "GET" | "POST") => apiFetch(\`/api/demo/channels\${path}\`, { method });
      api(slug, "/auth/me");
      api(slug, "/auth/login", { email });
      api(slug, "/auth/me", unknownValue);
      channelCall("/telegram", "POST");
    `);
    expect(
      out.map((c: { method: string; paths?: string[]; methodDynamic?: boolean }) => [
        c.method,
        c.paths,
        !!c.methodDynamic,
      ])
    ).toEqual([
      ["GET", ["/api/public/apps/{}/auth/me"], false],
      ["POST", ["/api/public/apps/{}/auth/login"], false],
      ["GET", ["/api/public/apps/{}/auth/me"], true],
      ["POST", ["/api/demo/channels/telegram"], false],
    ]);
  });

  it("resolves multi-statement builders, conditionals and a null branch", () => {
    const out = extract(`
      function commentsPath(id: string, commentId?: string): string {
        const base = \`/api/workflows/\${encodeURIComponent(id)}/comments\`;
        return commentId ? \`\${base}/\${encodeURIComponent(commentId)}\` : base;
      }
      apiFetch(commentsPath(wf, c), { method: "DELETE" });
      fetchPaged(allowed ? "/api/admin/issues" : null, 10);
      apiFetch(entityId ? \`/api/briefs?entityId=\${enc(entityId)}\` : "/api/briefs");
    `);
    expect(out[0].paths).toEqual(["/api/workflows/{}/comments/{}", "/api/workflows/{}/comments"]);
    expect(out[1].paths).toEqual(["/api/admin/issues"]);
    expect(out[2].paths).toEqual(["/api/briefs"]);
  });

  it("keeps a wrapper call with an unreadable argument as a dynamic call site at the caller", () => {
    const out = extract(`
      async function call(path: string) { return apiFetch(path); }
      call(somethingComputed);
    `);
    expect(out.map((c: { dynamic?: boolean }) => !!c.dynamic)).toEqual([true]);
  });

  it("matches templates segment by segment", () => {
    expect(normalizeTemplate("/api/agents/{agentId}/x/{id}:cancel")).toBe("/api/agents/{}/x/{}:cancel");
    expect(pathMatches("/api/agents/{}", "/api/agents/{agentId}")).toBe(true);
    expect(pathMatches("/api/purposes/{}/{}", "/api/purposes/agents/{id}")).toBe(true);
    expect(pathMatches("/api/agents/import", "/api/agents/{agentId}")).toBe(true);
    expect(pathMatches("/api/agents", "/api/agents/{agentId}")).toBe(false);
    expect(pathMatches("/api/tasks/{}:cancel", "/api/tasks/{id}:cancel")).toBe(true);
    expect(pathMatches("/api/tasks/abc:cancel", "/api/tasks/{id}:cancel")).toBe(true);
  });
});
