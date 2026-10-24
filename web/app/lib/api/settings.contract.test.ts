// @vitest-environment node
// The settings client against the contract and the provider's OpenAPI document. The client takes its fetcher as an
// argument, so the static extractor cannot read it (see contracts/consumers/web/manual.contract.json); this test does
// the extractor's job at run time: call every client method through a recording fetcher, then require that
//   1. each request (method + path) is a declared contract interaction,
//   2. the body it sent only uses fields the contract lists, and the fields the client reads from the reply are listed,
//   3. the provider's docs/api/openapi.json declares that operation.
// core's ConsumerContractVerificationTest checks the same contract entries from the provider side.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { pathMatches, readContractInteractions } from "../../../scripts/api-contract/extract.mjs";
import { createSettingsClient } from "./settings";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const contractDir = path.resolve(webRoot, "../contracts/consumers/web");
const openapi = JSON.parse(fs.readFileSync(path.resolve(webRoot, "../docs/api/openapi.json"), "utf8")) as {
  paths: Record<string, Record<string, unknown>>;
};

type Interaction = { id: string; method: string; path: string; request?: { requiredFields: string[] }; response?: { requiredFields: string[] } };
const interactions = readContractInteractions(contractDir) as Interaction[];

interface Recorded {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
}

function recordingClient() {
  const calls: Recorded[] = [];
  const fetcher = async (url: string, options?: { method?: string; body?: unknown }) => {
    const method = (options?.method ?? "GET").toUpperCase();
    calls.push({ method, path: url.split("?")[0], body: typeof options?.body === "string" ? JSON.parse(options.body) : null });
    const body = url.includes("/catalog")
      ? { entries: [] }
      : url.includes("/effective")
        ? { values: {} }
        : /\/settings\/history$/.test(url.split("?")[0])
          ? { entries: [], total: 0, offset: 0, limit: 100, hasMore: false }
          : url.includes("/history")
            ? []
            : {};
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
  };
  return { calls, client: createSettingsClient(fetcher as never) };
}

const AGENT = "agent-1";
const KEY = "agent.limits.maxToolSteps";
/** The concrete agent id and setting key the test used become `{}` placeholders, as in the contract. */
const generalize = (p: string) => p.split(AGENT).join("{}").split(KEY).join("{}");

async function exerciseEveryMethod() {
  const { calls, client } = recordingClient();
  await client.getCatalog();
  await client.getEffective();
  await client.getEffective(AGENT);
  await client.put("tenant", KEY, { value: 10, reason: "because" });
  await client.put("agent", KEY, { value: 10 }, AGENT);
  await client.reset("tenant", KEY);
  await client.reset("agent", KEY, AGENT);
  await client.getHistory(KEY, "tenant");
  await client.getHistory(KEY, "agent", AGENT);
  await client.reset("tenant", KEY, undefined, "reason is a query parameter, not a body field");
  await client.getHistoryPage();
  await client.getHistoryPage({ scope: "agent", agentId: AGENT, limit: 50, offset: 50, since: "2026-10-01T00:00:00Z" });
  return calls;
}

describe("settings client contract", () => {
  it("every request the client makes is a declared interaction of the web contract", async () => {
    const calls = await exerciseEveryMethod();
    expect(calls).toHaveLength(12);
    const missing = calls.filter((c) => !interactions.some((i) => i.method === c.method && pathMatches(generalize(c.path), i.path))).map((c) => `${c.method} ${c.path}`);
    expect(missing).toEqual([]);
  });

  it("the body the client sends uses only fields the contract lists for that request", async () => {
    const calls = await exerciseEveryMethod();
    const writes = calls.filter((c) => c.body);
    expect(writes).toHaveLength(2);
    for (const w of writes) {
      const entry = interactions.find((i) => i.method === w.method && pathMatches(generalize(w.path), i.path));
      expect(entry, `${w.method} ${w.path}`).toBeDefined();
      const allowed = new Set((entry!.request?.requiredFields ?? []).map((f) => f.replace(/^\$\./, "")));
      for (const field of Object.keys(w.body!)) expect(allowed.has(field), `${entry!.id} does not list request field ${field}`).toBe(true);
    }
  });

  it("the fields the client reads from each reply are listed in the contract", () => {
    const catalog = interactions.find((i) => i.id === "manual-get-api-settings-catalog")!;
    const read = ["key", "group", "labelKey", "descriptionKey", "helpKey", "type", "unit", "min", "max", "step", "enumValues", "risk", "scopes", "default", "minRole"];
    for (const f of read) expect(catalog.response!.requiredFields, `catalog.${f}`).toContain(`$.entries[*].${f}`);
    const effective = interactions.find((i) => i.id === "manual-get-api-settings-effective")!;
    expect(effective.response!.requiredFields).toContain("$.values");
  });

  it("the provider's OpenAPI document declares every operation the client calls", async () => {
    const calls = await exerciseEveryMethod();
    const undeclared = calls
      .filter((c) => !Object.entries(openapi.paths).some(([template, ops]) => pathMatches(generalize(c.path), template) && c.method.toLowerCase() in ops))
      .map((c) => `${c.method} ${c.path}`);
    expect(undeclared).toEqual([]);
  });
});
