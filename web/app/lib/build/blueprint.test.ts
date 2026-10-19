import { describe, expect, it } from "vitest";
import {
  FIELDS,
  addStep,
  blueprintFromGoal,
  composeInstructions,
  fieldsForLevel,
  moveStep,
  needsAutonomyPatch,
  pickTemplateId,
  postApplyAgentPatches,
  readiness,
  removeStep,
  toAppPrompt,
  toBrief,
  toCreateAgentPayload,
  validate,
  withAgent,
  withApp,
  withOutput,
  withWiring,
  withWorkflow,
  workflowInstruction,
} from "./blueprint";
import { applyRefinement, blueprintFromDraft } from "./edgeDraft";

describe("blueprint", () => {
  it("picks the lead recipe with a workflow from a lead goal", () => {
    const bp = blueprintFromGoal("follow up every new sales lead");
    expect(bp.recipeId).toBe("leadflow");
    expect(bp.workflow?.steps.some((s) => s.kind === "approval")).toBe(true);
  });

  it("levels are nested and Advanced shows every field for the current output", () => {
    const s = fieldsForLevel("simple").length;
    const st = fieldsForLevel("standard").length;
    expect(s).toBeLessThan(st);
    expect(fieldsForLevel("advanced")).toHaveLength(FIELDS.filter((f) => (f.output ?? "agent") === "agent").length);
    // The app output has its own rows and none of the agent tuning dials.
    const appRows = fieldsForLevel("advanced", "app");
    expect(appRows.length).toBeGreaterThan(0);
    expect(appRows.every((f) => f.section === "app")).toBe(true);
  });

  it("marks an edited field as set by the person and applies it to the payload", () => {
    const bp = withAgent(blueprintFromGoal("customer support"), { temperature: 0.2, seed: 7, topK: 40 }, "agent.temperature");
    expect(bp.sources["agent.temperature"]).toBe("you");
    const p = toCreateAgentPayload(bp, "OPENAI");
    expect(p).toMatchObject({ temperature: 0.2, seed: 7, topK: 40, providerName: "OPENAI", experienceMode: "SIMPLE" });
  });

  it("omits unset optional sampling fields", () => {
    const p = toCreateAgentPayload(blueprintFromGoal("hello"), "ANTHROPIC");
    for (const k of ["topK", "seed", "stopSequences", "model", "frequencyPenalty", "presencePenalty"]) expect(p).not.toHaveProperty(k);
  });

  it("only patches autonomy when it differs from the platform default", () => {
    const bp = blueprintFromGoal("hello");
    expect(needsAutonomyPatch(bp)).toBe(false);
    expect(needsAutonomyPatch(withAgent(bp, { autonomyTier: "SUPERVISED" }, "agent.autonomyTier"))).toBe(true);
  });

  it("instruction override beats base + tone", () => {
    const bp = withAgent(blueprintFromGoal("hello"), { instructionsOverride: "Only say hi." }, "agent.instructions");
    expect(composeInstructions(bp)).toBe("Only say hi.");
  });

  it("edits the flow, keeping at least two steps and the result last", () => {
    let bp = withWorkflow(blueprintFromGoal("customer support"), true);
    const n = bp.workflow!.steps.length;
    bp = addStep(bp, "approval");
    expect(bp.workflow!.steps).toHaveLength(n + 1);
    expect(bp.workflow!.steps.at(-1)!.kind).toBe("output");
    bp = moveStep(bp, 0, 1);
    expect(bp.workflow!.steps[1].kind).toBe("trigger");
    while (bp.workflow!.steps.length > 2) bp = removeStep(bp, 0);
    expect(removeStep(bp, 0).workflow!.steps).toHaveLength(2);
    expect(workflowInstruction(bp)).toContain("1.");
  });

  it("validates a blank name and readiness blocks with no model", () => {
    const bp = withAgent(blueprintFromGoal("hello"), { name: " " }, "agent.name");
    expect(validate(bp).errors).toContain("Give the agent a name.");
    expect(readiness(bp, { hasProviderKey: false, platformModelAvailable: false })[0].state).toBe("blocked");
    expect(readiness(bp, { hasProviderKey: false, platformModelAvailable: true })[0].state).toBe("ready");
  });
});

describe("blueprint -> brief (the envoy path)", () => {
  it("compiles agent fields into a brief-schema agent plus a starter eval set", () => {
    const bp = withAgent(blueprintFromGoal("answer pricing questions"), { temperature: 0.2, maxTokens: 512 }, "agent.temperature");
    const brief = toBrief(bp);
    expect(brief.briefVersion).toBe(1);
    expect(brief.trust.rung).toBe("UNDER_WATCH");
    expect(brief.agents).toHaveLength(1);
    expect(brief.agents[0]).toMatchObject({
      ref: "a1",
      name: bp.agent.name,
      reasoningDepth: bp.agent.reasoningDepth,
      generation: { temperature: 0.2, maxTokens: 512 },
    });
    expect(brief.evalSet?.targetAgent).toBe("a1");
    // brief-schema requires 4-16 cases and an armed gate.
    expect(brief.evalSet?.cases.length).toBeGreaterThanOrEqual(4);
    expect(brief.evalSet?.cases.every((c) => c.input && c.rubric)).toBe(true);
    expect(brief.evalSet?.gate?.minScore).toBeGreaterThan(0);
  });

  it("maps autonomy tiers onto real humanMustDecide node paths and workflow steps onto a procedure", () => {
    let bp = withAgent(blueprintFromGoal("follow up leads"), { autonomyTier: "SUPERVISED" }, "agent.autonomyTier");
    let brief = toBrief(bp);
    // brief-schema takes wX.<nodeId> paths, never prose; the side-effecting
    // steps (action/output) are what a person still decides under SUPERVISED.
    const nodeIds = bp.workflow!.steps.map((s) => s.id);
    for (const p of brief.trust.humanMustDecide ?? []) {
      expect(p).toMatch(/^w\d{1,2}\.[A-Za-z0-9_-]+$/);
      expect(nodeIds).toContain(p.split(".")[1]);
    }
    expect(brief.trust.humanMustDecide!.length).toBeGreaterThan(0);
    expect(brief.procedures?.[0].steps.length).toBe(bp.workflow!.steps.length);
    bp = withAgent(bp, { autonomyTier: "FULLY_AUTONOMOUS" }, "agent.autonomyTier");
    brief = toBrief(bp);
    expect(brief.trust.humanMustDecide).toHaveLength(0);
  });

  it("writes a narrative entry for every ref the brief declares", () => {
    let bp = withWiring(blueprintFromGoal("follow up every new sales lead"), {
      channels: ["WEB", "WHATSAPP"],
      secretsText: "crm api key - read the CRM",
      mcpsText: "github-mcp",
      toolsText: "lookup order - fetch order status key: CRM_API_KEY",
      trigger: "MANUAL",
    }, "wiring");
    bp = withAgent(bp, { knowledgeBase: true }, "agent.knowledgeBase");
    const brief = toBrief(bp);
    const declared = [
      "a1",
      ...(brief.workflows ?? []).map((w) => w.ref),
      ...(brief.procedures ?? []).map((p) => p.ref),
      ...(brief.knowledge ?? []).map((k) => k.ref),
      ...(brief.channels ?? []).map((c) => c.ref),
      ...(brief.secrets ?? []).map((s) => s.ref),
      ...(brief.tools ?? []).map((t) => t.ref),
      ...(brief.mcpServers ?? []).map((m) => m.ref),
    ];
    for (const ref of declared) {
      const entry = brief.narrative.perNode[ref];
      expect(entry, `missing narrative for ${ref}`).toBeTruthy();
      expect(entry.why && entry.tune && entry.proof).toBeTruthy();
    }
  });

  it("carries only the fields the brief cannot express into post-apply patches", () => {
    const bp = blueprintFromGoal("hello");
    expect(postApplyAgentPatches(bp)).toBeNull();
    const tuned = withAgent(withAgent(bp, { topK: 40, seed: 9 }, "agent.topK"), { model: "gpt-5" }, "agent.model");
    expect(postApplyAgentPatches(tuned)).toMatchObject({ generation: { topK: 40, seed: 9 }, model: "gpt-5" });
  });

  it("builds the whole envoy: channels, secrets, tools, mcp and a real workflow graph", () => {
    let bp = withWiring(blueprintFromGoal("follow up every new sales lead"), { channels: ["WEB", "WHATSAPP"] }, "wiring.channels");
    bp = withWiring(bp, {
      secretsText: "crm api key - read the CRM",
      mcpsText: "github-mcp",
      toolsText: "lookup order - fetch order status key: CRM_API_KEY\norders api https://api.example.com/openapi.json",
      trigger: "SCHEDULE",
      cron: "15 8 * * *",
    }, "wiring");
    const brief = toBrief(bp);
    expect(brief.channels).toHaveLength(2);
    expect(brief.channels?.[0]).toMatchObject({ kind: "WEB", bindTo: "a1" });
    expect(brief.secrets?.[0]).toMatchObject({ name: "CRM_API_KEY", scope: "TENANT" });
    expect(brief.mcpServers?.[0].registryId).toBe("github-mcp");
    expect(brief.tools).toHaveLength(2);
    expect(brief.tools?.[0]).toMatchObject({ kind: "CODE", auth: { type: "API_KEY_HEADER", secretRef: "s1" } });
    expect(brief.tools?.[1]).toMatchObject({ kind: "OPENAPI", specUrl: "https://api.example.com/openapi.json" });
    // Real graph: agent nodes carry the a1 ref; the trigger carries the cron.
    const wf = brief.workflows[0];
    const nodes = wf.graph.nodes as { type: string; data: { config?: Record<string, unknown> } }[];
    expect(nodes.some((n) => n.type === "agent" && n.data.config?.agentId === "a1")).toBe(true);
    expect(nodes.some((n) => n.type === "approval")).toBe(true);
    expect((wf.graph.edges as unknown[]).length).toBe(nodes.length - 1);
    expect(wf.triggers?.[0]).toMatchObject({ kind: "SCHEDULE", cron: "15 8 * * *" });
  });

  it("validates wiring: cron required for schedules, channel trigger needs a channel, bad secret names warn", () => {
    let bp = withWiring(blueprintFromGoal("digest"), { trigger: "SCHEDULE", cron: "  " }, "wiring.trigger");
    expect(validate(bp).errors).toContain("A scheduled envoy needs a cron expression.");
    bp = withWiring(bp, { trigger: "CHANNEL", channels: [] }, "wiring.trigger");
    expect(validate(bp).errors).toContain("A message-triggered envoy needs at least one channel ticked.");
    bp = withWiring(bp, { trigger: "MANUAL", secretsText: "1bad - starts with a digit" }, "wiring.secretsText");
    expect(validate(bp).warnings.some((w) => /not a valid vault name/i.test(w))).toBe(true);
  });
});

describe("blueprint -> app scaffold (the Git path)", () => {
  it("guesses the template from the goal and composes the scaffold prompt", () => {
    expect(pickTemplateId("an android app for field reps")).toBe("capacitor-app");
    expect(pickTemplateId("a marketing site for my bakery")).toBe("astro-site");
    expect(pickTemplateId("an internal dashboard")).toBe("node-react");

    const bp = withApp(
      withApp(withOutput(blueprintFromGoal("orders tracker"), "app"), { features: "login page\ndark mode" }, "app.features"),
      { tagline: "Know every order" },
      "app.tagline"
    );
    const prompt = toAppPrompt(bp);
    expect(prompt).toContain("orders tracker");
    expect(prompt).toContain("- login page");
    expect(prompt).toContain("Know every order");
    expect(prompt).toContain(bp.app.accent);
  });

  it("validates app-mode errors instead of agent ones", () => {
    const bp = withApp(withOutput(blueprintFromGoal("hello"), "app"), { name: " " }, "app.name");
    expect(validate(bp).errors).toContain("Give the app a name.");
    expect(validate(bp).errors).not.toContain("Give the agent a name.");
    const bad = withApp(bp, { accent: "blue" }, "app.accent");
    expect(validate(bad).warnings.some((w) => /hex/i.test(w))).toBe(true);
  });
});

describe("on-device draft validation", () => {
  it("never trusts model output outside the closed lists", () => {
    const bp = blueprintFromDraft("support on whatsapp", {
      recipe: "nonsense", name: "Ava", tone: "shouty", creativity: "wild", autonomy: "GOD_MODE",
      usesDocuments: true, needsWorkflow: false,
    })!;
    expect(bp.recipeId).toBe("general");
    expect(bp.agent.name).toBe("Ava");
    expect(bp.agent.tone).toBe("friendly");
    expect(bp.agent.autonomyTier).toBe("SEMI_AUTONOMOUS");
    expect(bp.sources["agent.name"]).toBe("device-ai");
  });

  it("rejects non-objects and keeps the recipe flow when drafted steps are unusable", () => {
    expect(blueprintFromDraft("x", "junk")).toBeNull();
    const bp = blueprintFromDraft("leads", {
      recipe: "leadflow", name: "Q", tone: "concise", creativity: "steady", autonomy: "SUPERVISED",
      usesDocuments: false, needsWorkflow: true, steps: [{ kind: "agent", label: "no trigger first" }],
    })!;
    expect(bp.workflow!.steps[0].kind).toBe("trigger");
    expect(bp.agent.temperature).toBe(0.2);
  });

  it("applies a refinement by adding an approval step once", () => {
    const base = blueprintFromGoal("customer support");
    const once = applyRefinement(base, { tone: "professional", addApprovalStep: true });
    const twice = applyRefinement(once, { addApprovalStep: true });
    expect(once.agent.tone).toBe("professional");
    expect(twice.workflow!.steps.filter((s) => s.kind === "approval")).toHaveLength(1);
  });
});
