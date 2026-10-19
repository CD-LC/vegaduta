import { test, expect } from "@playwright/test";
import { API_URL } from "../helpers/prod-env";
import { authHeaders, parseChatSse } from "../helpers/prod-api";
import { setupControlSweep, NO_LEAK } from "../helpers/control-sweep";

/**
 * CONTROL SWEEP - data-driven pass over every control that
 * e2e/CONTROL-COVERAGE-MATRIX.md lists as "none" or "presence-only".
 *
 * Shape of every case: CHANGE the control (through the same REST endpoint the
 * UI control calls - web/app/lib/api/agents.ts, workflows.ts), READ IT BACK
 * from a fresh GET (i.e. what a reload would show), and where a deterministic
 * behavior exists (guardrails, sampling bounds, validation, fan-out cap, human
 * pause) assert the BEHAVIOR changed, not only the stored value. A final
 * table sweeps invalid payloads and asserts none returns a 5xx or leaks a
 * stack trace.
 *
 * Plan-gated controls (402/403) are annotated "plan-gated" and not counted as
 * failures - the tenant plan decides those, not the control.
 *
 * Every test creates its own uniquely named agents/workflows and deletes them.
 * Writes real rows to the tenant, so it is gated like the other *-live specs:
 * E2E_USER_EMAIL/E2E_USER_PASSWORD + E2E_ALLOW_MUTATIONS=1, chromium only.
 *
 * This file covers the agent workbench; workflow designer and build studio
 * controls are in control-sweep-live-workflows.spec.ts. Shared setup:
 * helpers/control-sweep.ts.
 */

test.describe("Control sweep - untested agent workbench controls (live)", () => {
  test.setTimeout(4 * 60 * 1000);

  const sweep = setupControlSweep();
  const { api, gated, newAgent, getAgent, agents } = sweep;

  // ---------------------------------------------------------------------------------------------
  // AGENT WORKBENCH
  // ---------------------------------------------------------------------------------------------

  const TOGGLES = [
    { control: "Response caching", path: "response-caching", field: "responseCachingEnabled" },
    { control: "Semantic response caching", path: "semantic-response-caching", field: "semanticResponseCachingEnabled", needs: "response-caching" },
    { control: "Share memory with workspace", path: "share-memory", field: "shareMemoryWithTenant" },
    { control: "Biometric consent", path: "biometric-consent", field: "biometricConsentEnabled" },
    { control: "Knowledge base attach", path: "knowledge-base", field: "knowledgeBaseEnabled" },
  ] as const;

  test("workbench boolean toggles: every one persists ON, persists OFF, and survives a fresh read", async ({ page }) => {
    const a = await newAgent(page);
    for (const t of TOGGLES) {
      if ("needs" in t) await api(page, "PATCH", `/api/agents/${a.id}/${t.needs}`, { enabled: true });
      const on = await api(page, "PATCH", `/api/agents/${a.id}/${t.path}`, { enabled: true });
      if (gated(on, t.control)) continue;
      expect(on.status, `${t.control} ON -> ${on.status} ${on.text.slice(0, 200)}`).toBeLessThan(300);
      expect((await getAgent(page, a.id))[t.field], `${t.control} did not persist ON`).toBe(true);
      const off = await api(page, "PATCH", `/api/agents/${a.id}/${t.path}`, { enabled: false });
      expect(off.status, `${t.control} OFF -> ${off.status}`).toBeLessThan(300);
      expect((await getAgent(page, a.id))[t.field], `${t.control} did not persist OFF`).toBe(false);
    }
  });

  test("tuning mode + experience mode: both values persist and read back", async ({ page }) => {
    const a = await newAgent(page);
    for (const [path, key, values] of [
      ["tuning-mode", "tuningMode", ["MANUAL", "SIMPLE"]],
      ["experience-mode", "experienceMode", ["ADVANCED", "SIMPLE"]],
    ] as const) {
      for (const v of values) {
        const r = await api(page, "PATCH", `/api/agents/${a.id}/${path}`, { [key]: v });
        if (gated(r, path)) break;
        expect(r.status, `${path}=${v} -> ${r.status} ${r.text.slice(0, 200)}`).toBeLessThan(300);
        expect(String((await getAgent(page, a.id))[key]).toUpperCase(), `${path}=${v} did not persist`).toBe(v);
      }
    }
  });

  test("agent tier: each tier is accepted and never 5xx", async ({ page }) => {
    const a = await newAgent(page);
    for (const tier of ["CONSUMER", "PROFESSIONAL", "EXPERT"]) {
      const r = await api(page, "PATCH", `/api/agents/${a.id}/tier`, { tier });
      if (gated(r, `tier ${tier}`)) continue;
      expect(r.status, `tier ${tier} -> ${r.status} ${r.text.slice(0, 200)}`).toBeLessThan(300);
      // The workbench has no tier read-back field today; assert it at least did not corrupt the agent.
      expect((await getAgent(page, a.id)).id).toBe(a.id);
    }
  });

  test("advanced sampling: top-K, stop sequences, penalties and seed persist, clear, and out-of-range values are rejected without changing state", async ({ page }) => {
    const a = await newAgent(page);
    await api(page, "PATCH", `/api/agents/${a.id}/tuning-mode`, { tuningMode: "MANUAL" });
    const base = { maxTokens: 512, temperature: 0.4, topP: 0.9 };
    const set = await api(page, "PATCH", `/api/agents/${a.id}/generation-params`, {
      ...base,
      topK: 40,
      stopSequences: ["END", "STOP NOW"],
      frequencyPenalty: 0.5,
      presencePenalty: -0.5,
      seed: 42,
    });
    if (gated(set, "advanced sampling")) return;
    expect(set.status, `${set.status} ${set.text.slice(0, 300)}`).toBeLessThan(300);
    const got = await getAgent(page, a.id);
    expect(got.topK, "topK").toBe(40);
    expect(got.stopSequences, "stopSequences").toEqual(["END", "STOP NOW"]);
    expect(got.frequencyPenalty, "frequencyPenalty").toBeCloseTo(0.5);
    expect(got.presencePenalty, "presencePenalty").toBeCloseTo(-0.5);
    expect(got.seed, "seed").toBe(42);
    expect(got.maxTokens).toBe(512);
    expect(got.temperature).toBeCloseTo(0.4);
    expect(got.topP).toBeCloseTo(0.9);

    const BAD: Array<[string, Record<string, unknown>]> = [
      ["topK 0", { topK: 0 }],
      ["topK 1001", { topK: 1001 }],
      ["frequencyPenalty 2.5", { frequencyPenalty: 2.5 }],
      ["presencePenalty -2.5", { presencePenalty: -2.5 }],
      ["9 stop sequences", { stopSequences: ["a", "b", "c", "d", "e", "f", "g", "h", "i"] }],
      ["blank stop sequence", { stopSequences: ["   "] }],
      ["65-char stop sequence", { stopSequences: ["x".repeat(65)] }],
      ["temperature 1.5", { temperature: 1.5 }],
      ["topP -0.1", { topP: -0.1 }],
      ["maxTokens 8193", { maxTokens: 8193 }],
      ["maxTokens 0", { maxTokens: 0 }],
    ];
    for (const [label, patch] of BAD) {
      const r = await api(page, "PATCH", `/api/agents/${a.id}/generation-params`, { ...base, ...patch });
      expect(r.status, `${label} should be a 4xx, got ${r.status}`).toBeGreaterThanOrEqual(400);
      expect(r.status, `${label} produced a server error`).toBeLessThan(500);
      expect(r.text, `${label} leaked internals`).not.toMatch(NO_LEAK);
    }
    const after = await getAgent(page, a.id);
    expect(after.topK, "a rejected request changed topK").toBe(40);
    expect(after.maxTokens, "a rejected request changed maxTokens").toBe(512);

    const cleared = await api(page, "PATCH", `/api/agents/${a.id}/generation-params`, { ...base, clearSamplingParams: true });
    expect(cleared.status).toBeLessThan(300);
    const c = await getAgent(page, a.id);
    expect(c.topK ?? null, "clearSamplingParams left topK").toBeNull();
    expect(c.seed ?? null, "clearSamplingParams left seed").toBeNull();
    expect(c.frequencyPenalty ?? null).toBeNull();
    expect(c.presencePenalty ?? null).toBeNull();
    expect((c.stopSequences ?? []).length, "clearSamplingParams left stop sequences").toBe(0);
  });

  test("cost routing and fallback model: values persist, thresholds round-trip, disabling clears routing", async ({ page }) => {
    const a = await newAgent(page);
    const cr = await api(page, "PATCH", `/api/agents/${a.id}/cost-routing`, {
      enabled: true,
      economyProviderName: sweep.template!.providerName,
      economyModel: sweep.template!.model,
      jevThreshold: 0.6,
    });
    if (!gated(cr, "cost routing")) {
      expect(cr.status, `cost-routing -> ${cr.status} ${cr.text.slice(0, 200)}`).toBeLessThan(300);
      const g = await getAgent(page, a.id);
      expect(g.costRoutingEnabled, "cost routing ON not persisted").toBe(true);
      expect(g.economyModel, "economy model not persisted").toBe(sweep.template!.model);
      expect(g.costRoutingJevThreshold, "JEV threshold not persisted").toBeCloseTo(0.6);
      const off = await api(page, "PATCH", `/api/agents/${a.id}/cost-routing`, {
        enabled: false,
        economyProviderName: null,
        economyModel: null,
        jevThreshold: null,
      });
      expect(off.status).toBeLessThan(300);
      const g2 = await getAgent(page, a.id);
      expect(g2.costRoutingEnabled, "cost routing OFF not persisted").toBe(false);
      expect(g2.economyModel ?? null, "economy model not cleared").toBeNull();
    }

    // The server REJECTS a fallback equal to the primary (AgentService.setFallbackProvider), so pick a
    // different model from the provider's catalog (platform-pool tenants may only use catalog models).
    const cat = await api(page, "GET", `/api/providers/${sweep.template!.providerName}/models`);
    const rows: any[] = Array.isArray(cat.json) ? cat.json : cat.json?.models ?? [];
    const primary = (await getAgent(page, a.id)).model;
    const candidates = rows.map((m) => m.modelId ?? m.id ?? m.model).filter((m) => m && m !== sweep.template!.model && m !== primary);
    test.skip(!candidates.length, "provider catalog offers no second model to use as a fallback");
    let fbModel = candidates[0];
    let fb = await api(page, "PATCH", `/api/agents/${a.id}/fallback`, { providerName: sweep.template!.providerName, model: fbModel });
    for (let i = 1; fb.status === 400 && i < candidates.length; i++) {
      fbModel = candidates[i];
      fb = await api(page, "PATCH", `/api/agents/${a.id}/fallback`, { providerName: sweep.template!.providerName, model: fbModel });
    }
    if (!gated(fb, "fallback")) {
      expect(fb.status, `fallback -> ${fb.status} ${fb.text.slice(0, 200)}`).toBeLessThan(300);
      const g = await getAgent(page, a.id);
      expect(g.fallbackModel, "fallback model not persisted").toBe(fbModel);
      const clear = await api(page, "PATCH", `/api/agents/${a.id}/fallback`, { providerName: null, model: null });
      expect(clear.status, `clearing fallback -> ${clear.status} ${clear.text.slice(0, 200)}`).toBeLessThan(300);
      expect((await getAgent(page, a.id)).fallbackModel ?? null, "fallback not cleared").toBeNull();
    }
  });

  test("collaborators and knowledge scope: assignments persist and clear; a self-collaborator is not stored", async ({ page }) => {
    const a = await newAgent(page);
    const b = await newAgent(page);
    const set = await api(page, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [b.id] });
    if (!gated(set, "collaborators")) {
      expect(set.status, `${set.status} ${set.text.slice(0, 200)}`).toBeLessThan(300);
      expect((await getAgent(page, a.id)).collaboratorAgentIds, "collaborator not persisted").toContain(b.id);
      const self = await api(page, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [a.id] });
      expect(self.status, "self-collaborator produced a server error").toBeLessThan(500);
      if (self.status < 300) {
        expect((await getAgent(page, a.id)).collaboratorAgentIds, "agent stored itself as its own collaborator").not.toContain(a.id);
      }
      await api(page, "PATCH", `/api/agents/${a.id}/collaborators`, { collaboratorAgentIds: [] });
      expect((await getAgent(page, a.id)).collaboratorAgentIds ?? [], "collaborators not cleared").toEqual([]);
    }

    const cols = await api(page, "GET", "/api/knowledge/collections");
    if (Array.isArray(cols.json) && cols.json.length) {
      const id = cols.json[0].id;
      const k = await api(page, "PATCH", `/api/agents/${a.id}/knowledge-access`, { allowedKnowledgeCollectionIds: [id] });
      if (!gated(k, "knowledge access")) {
        expect(k.status).toBeLessThan(300);
        expect((await getAgent(page, a.id)).allowedKnowledgeCollectionIds, "knowledge scope not persisted").toContain(id);
        await api(page, "PATCH", `/api/agents/${a.id}/knowledge-access`, { allowedKnowledgeCollectionIds: [] });
        expect((await getAgent(page, a.id)).allowedKnowledgeCollectionIds ?? []).toEqual([]);
      }
    } else {
      test.info().annotations.push({ type: "note", description: "tenant has no knowledge collection - knowledge-access scope not exercised" });
    }
  });

  test("capabilities: a capability toggled through the workbench endpoint reads back and reverts", async ({ page }) => {
    const a = await newAgent(page);
    const list = await api(page, "GET", `/api/agents/${a.id}/capabilities`);
    expect(list.status).toBe(200);
    expect(Array.isArray(list.json) && list.json.length > 0, "agent exposes no capabilities at all").toBeTruthy();
    const usable = (list.json as any[]).find((c) => String(c.availability ?? "AVAILABLE").toUpperCase().startsWith("AVAIL"));
    test.skip(!usable, "no capability is available to this tenant/plan");
    const flipTo = !usable.enabled;
    const r = await api(page, "PATCH", `/api/agents/${a.id}/capabilities/${encodeURIComponent(usable.key)}`, { enabled: flipTo });
    if (gated(r, `capability ${usable.key}`)) return;
    expect(r.status, `${r.status} ${r.text.slice(0, 200)}`).toBeLessThan(300);
    const after = ((await api(page, "GET", `/api/agents/${a.id}/capabilities`)).json as any[]).find((c) => c.key === usable.key);
    expect(after.enabled, `capability ${usable.key} did not persist`).toBe(flipTo);
    await api(page, "PATCH", `/api/agents/${a.id}/capabilities/${encodeURIComponent(usable.key)}`, { enabled: usable.enabled });
    const restored = ((await api(page, "GET", `/api/agents/${a.id}/capabilities`)).json as any[]).find((c) => c.key === usable.key);
    expect(restored.enabled).toBe(usable.enabled);
  });

  test("guardrails: every available guardrail toggles and persists; PII guardrail really redacts a reply", async ({ page }) => {
    const marker = "john.doe.sweep@example.com";
    const a = await newAgent(
      page,
      `Whatever the user says, reply with exactly this one line and nothing else: Contact us at ${marker} today.`
    );
    const g = await api(page, "GET", `/api/agents/${a.id}/guardrails`);
    expect(g.status).toBe(200);
    const available: string[] = g.json.availableGuardrails ?? [];
    const locked = new Set<string>(g.json.lockedGuardrails ?? []);
    expect(available.length, "no guardrails offered").toBeGreaterThan(0);

    // Each unlocked guardrail toggles on, persists, toggles off.
    for (const name of available.filter((n) => !locked.has(n))) {
      const on = await api(page, "PATCH", `/api/agents/${a.id}/guardrails`, { guardrails: [name] });
      expect(on.status, `enable ${name} -> ${on.status} ${on.text.slice(0, 200)}`).toBeLessThan(300);
      expect((await api(page, "GET", `/api/agents/${a.id}/guardrails`)).json.enabledGuardrails, `${name} not persisted`).toContain(name);
    }
    // Locked ones must be refused, not silently stored.
    for (const name of locked) {
      const r = await api(page, "PATCH", `/api/agents/${a.id}/guardrails`, { guardrails: [name] });
      if (r.status < 300) {
        expect((await api(page, "GET", `/api/agents/${a.id}/guardrails`)).json.enabledGuardrails, `plan-locked guardrail ${name} was enabled`).not.toContain(name);
      }
    }
    await api(page, "PATCH", `/api/agents/${a.id}/guardrails`, { guardrails: [] });

    // BEHAVIOR: with pii OFF the reply carries the address; with pii ON it must not.
    const chat = async (msg: string) => {
      for (let i = 0; i < 3; i++) {
        const res = await page.request.post(`${API_URL}/api/agents/${a.id}/chat`, {
          headers: { ...authHeaders(sweep.token), "Content-Type": "application/json", Accept: "text/event-stream" },
          data: { message: msg },
          failOnStatusCode: false,
          timeout: 120_000,
        });
        const body = await res.text();
        const parsed = parseChatSse(body);
        if (res.status() !== 200 || parsed.replyText.trim()) return { status: res.status(), text: parsed.replyText, raw: body };
      }
      return { status: 0, text: "", raw: "" };
    };
    if (!available.includes("pii") || locked.has("pii")) {
      test.info().annotations.push({ type: "note", description: "pii guardrail unavailable/locked on this plan - redaction behavior not exercised" });
      return;
    }
    const off = await chat("Say it.");
    expect(off.text, "unguarded agent did not produce the marker; test premise invalid").toContain(marker);
    await api(page, "PATCH", `/api/agents/${a.id}/guardrails`, { guardrails: ["pii"] });
    const on = await chat("Say it.");
    expect(on.raw, "PII guardrail ON but the email address still reached the user").not.toContain(marker);
    expect(on.raw).not.toMatch(NO_LEAK);
  });

  test("specialties: tags persist and a bogus body is rejected without a 5xx", async ({ page }) => {
    const a = await newAgent(page);
    const tags = await api(page, "GET", "/api/agents/specialties/tags");
    expect(tags.status).toBe(200);
    const tag = (Array.isArray(tags.json) && tags.json[0]) || "e2e-sweep";
    const put = await api(page, "PUT", `/api/agents/${a.id}/specialties`, { specialties: [{ capabilityTag: tag, description: "sweep" }] });
    if (!gated(put, "specialties")) {
      expect(put.status, `${put.status} ${put.text.slice(0, 200)}`).toBeLessThan(300);
      const got = await api(page, "GET", `/api/agents/${a.id}/specialties`);
      expect(JSON.stringify(got.json), "specialty not persisted").toContain(tag);
      const bad = await api(page, "PUT", `/api/agents/${a.id}/specialties`, { specialties: "nope" });
      expect(bad.status).toBeLessThan(500);
    }
  });

  test("prompt versions: each prompt edit adds a version and Restore brings the old prompt back", async ({ page }) => {
    const a = await newAgent(page, "VERSION-ONE prompt. Reply briefly.");
    await api(page, "PATCH", `/api/agents/${a.id}/prompt`, { systemPrompt: "VERSION-TWO prompt. Reply briefly." });
    await api(page, "PATCH", `/api/agents/${a.id}/prompt`, { systemPrompt: "VERSION-THREE prompt. Reply briefly." });
    const list = await api(page, "GET", `/api/agents/${a.id}/prompt/versions`);
    expect(list.status).toBe(200);
    const v = list.json as Array<{ id: string; systemPrompt: string }>;
    expect(v.length, "each prompt edit should add a version").toBeGreaterThanOrEqual(2);
    const one = v.find((x) => x.systemPrompt.includes("VERSION-ONE"));
    expect(one, "the original prompt is missing from the version history").toBeTruthy();
    const rev = await api(page, "POST", `/api/agents/${a.id}/prompt/versions/${one!.id}/revert`);
    expect(rev.status, `${rev.status} ${rev.text.slice(0, 200)}`).toBeLessThan(300);
    expect((await getAgent(page, a.id)).systemPrompt, "restore did not bring the old prompt back").toContain("VERSION-ONE");
    // The config-version list (Versions card) must load for the same agent.
    const cfg = await api(page, "GET", `/api/agents/${a.id}/versions`);
    expect(cfg.status, "Versions card endpoint failed").toBeLessThan(300);
  });

  test("duplicate agent: the copy carries prompt and generation settings and is a different agent", async ({ page }) => {
    const a = await newAgent(page, "DUPLICATE-ME prompt.");
    await api(page, "PATCH", `/api/agents/${a.id}/tuning-mode`, { tuningMode: "MANUAL" });
    await api(page, "PATCH", `/api/agents/${a.id}/generation-params`, { maxTokens: 777, temperature: 0.3, topP: 0.8 });
    const d = await api(page, "POST", `/api/agents/${a.id}/duplicate`);
    if (gated(d, "duplicate")) return;
    expect(d.status, `${d.status} ${d.text.slice(0, 200)}`).toBeLessThan(300);
    agents.push(d.json.id);
    expect(d.json.id).not.toBe(a.id);
    const c = await getAgent(page, d.json.id);
    expect(c.systemPrompt).toContain("DUPLICATE-ME");
    expect(c.maxTokens, "copy lost maxTokens").toBe(777);
    expect(c.temperature).toBeCloseTo(0.3);
  });

  test("memory, tool-call log, reliability and code-intel scope endpoints answer cleanly on a fresh agent", async ({ page }) => {
    const a = await newAgent(page);
    for (const path of [
      `/api/agents/${a.id}/memories?subjectKey=sweep`,
      `/api/agents/${a.id}/tool-calls`,
      `/api/agents/${a.id}/reliability`,
      `/api/agents/${a.id}/available-tools`,
    ]) {
      const r = await api(page, "GET", path);
      expect(r.status, `${path} -> ${r.status} ${r.text.slice(0, 200)}`).toBeLessThan(400);
      expect(r.text).not.toMatch(NO_LEAK);
    }
    const ci = await api(page, "PATCH", `/api/agents/${a.id}/code-intel-repo-access`, { allowedCodeIntelRepoIds: [] });
    if (!gated(ci, "code-intel repo access")) expect(ci.status).toBeLessThan(400);
    const forget = await api(page, "DELETE", `/api/agents/${a.id}/memories/00000000-0000-0000-0000-000000000000`);
    expect(forget.status, "forgetting a non-existent memory must be a 4xx, not a 5xx").toBeLessThan(500);
  });

  test("invalid-input sweep: no workbench control returns a 5xx or leaks a stack trace on hostile input", async ({ page }) => {
    const a = await newAgent(page);
    const BAD: Array<[string, string, string, unknown]> = [
      ["model: bogus provider", "PATCH", "model", { providerName: "NOT_A_PROVIDER", model: "x" }],
      ["model: empty body", "PATCH", "model", {}],
      ["prompt: empty", "PATCH", "prompt", { systemPrompt: "" }],
      ["prompt: null", "PATCH", "prompt", { systemPrompt: null }],
      ["prompt: 300k chars", "PATCH", "prompt", { systemPrompt: "x".repeat(300_000) }],
      ["tuning-mode: bogus", "PATCH", "tuning-mode", { tuningMode: "TURBO" }],
      ["experience-mode: bogus", "PATCH", "experience-mode", { experienceMode: "TURBO" }],
      ["reasoning-depth: bogus", "PATCH", "reasoning-depth", { reasoningDepth: "TURBO" }],
      ["autonomy-tier: bogus", "PATCH", "autonomy-tier", { autonomyTier: "TURBO" }],
      ["tier: bogus", "PATCH", "tier", { tier: "TURBO" }],
      ["tool-access: unknown tool", "PATCH", "tool-access", { allowedToolNames: ["no_such_tool_xyz"] }],
      ["tool-access: not a list", "PATCH", "tool-access", { allowedToolNames: "fetch_web_page" }],
      ["knowledge-access: not a uuid", "PATCH", "knowledge-access", { allowedKnowledgeCollectionIds: ["nope"] }],
      ["knowledge-access: unknown uuid", "PATCH", "knowledge-access", { allowedKnowledgeCollectionIds: ["00000000-0000-0000-0000-000000000000"] }],
      ["collaborators: unknown uuid", "PATCH", "collaborators", { collaboratorAgentIds: ["00000000-0000-0000-0000-000000000000"] }],
      ["collaborators: not a uuid", "PATCH", "collaborators", { collaboratorAgentIds: ["nope"] }],
      ["guardrails: unknown name", "PATCH", "guardrails", { guardrails: ["no_such_guardrail"] }],
      ["cost-routing: enabled without economy model", "PATCH", "cost-routing", { enabled: true, economyProviderName: null, economyModel: null }],
      ["cost-routing: threshold 5", "PATCH", "cost-routing", { enabled: false, economyProviderName: null, economyModel: null, jevThreshold: 5 }],
      ["fallback: bogus provider", "PATCH", "fallback", { providerName: "NOT_A_PROVIDER", model: "x" }],
      ["voice-model: bogus", "PATCH", "voice-model", { voiceModelProvider: "NOPE", voiceModel: "x" }],
      ["tts-model: bogus", "PATCH", "tts-model", { ttsProvider: "NOPE", ttsModel: "x" }],
      ["video-model: bogus", "PATCH", "video-model", { videoModelProvider: "NOPE", videoModel: "x" }],
      ["capabilities: unknown key", "PATCH", "capabilities/no_such_capability", { enabled: true }],
      ["toggle: string not bool", "PATCH", "response-caching", { enabled: "yes" }],
      ["toggle: empty body", "PATCH", "share-memory", {}],
    ];
    const fivexx: string[] = [];
    for (const [label, method, path, body] of BAD) {
      const r = await api(page, method, `/api/agents/${a.id}/${path}`, body);
      if (r.status >= 500 || NO_LEAK.test(r.text)) fivexx.push(`${label} -> ${r.status} ${r.text.slice(0, 120)}`);
    }
    expect(fivexx, `hostile input caused server errors / leaks:\n${fivexx.join("\n")}`).toEqual([]);
    // The agent must still be readable and unchanged in identity after all that abuse.
    expect((await getAgent(page, a.id)).id).toBe(a.id);
  });

  test("workbench UI: Simple/Manual tuning buttons persist across reload", async ({ page }) => {
    const a = await newAgent(page);
    await page.goto("/agents", { waitUntil: "domcontentloaded" });
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    await page.getByText(a.name, { exact: false }).first().click();
    await expect(page.getByText("Generation tuning").first()).toBeVisible({ timeout: 30_000 });
    const manual = page.getByRole("button", { name: /^manual$/i }).first();
    await manual.scrollIntoViewIfNeeded();
    await manual.click();
    await expect.poll(async () => String((await getAgent(page, a.id)).tuningMode).toUpperCase(), {
      message: "clicking Manual did not save tuningMode=MANUAL",
      timeout: 20_000,
    }).toBe("MANUAL");
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.locator('button[aria-haspopup="listbox"][data-tour="create-agent"]').click();
    await page.getByText(a.name, { exact: false }).first().click();
    await expect(page.getByText("Generation tuning").first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: /^manual$/i }).first(), "Manual pill not selected after reload").toHaveClass(/border-primary/, {
      timeout: 15_000,
    });
  });

  // ---------------------------------------------------------------------------------------------
  // WORKFLOW DESIGNER
  // ---------------------------------------------------------------------------------------------
});
