import { describe, expect, it } from "vitest";
import { blueprintFromAiDraft, extractJson } from "./cloudDraft";
import { composeInstructions, validate } from "./blueprint";

const GOAL = "answer refund questions on whatsapp and ask me before refunding";

const REPLY = {
  recipe: "support",
  summary: "A refund desk that triages and asks the owner before paying out.",
  name: "Refund Desk Agent",
  instructions:
    "You are the refund desk for a small shop. Greet the customer, find the order number, and explain the refund policy you were given. Never promise a refund yourself: collect the details and hand the case to the owner for approval. If something is outside refunds, say so and pass it on.",
  tone: "professional",
  creativity: "steady",
  autonomy: "SUPERVISED",
  usesDocuments: true,
  needsWorkflow: true,
  steps: [
    { kind: "trigger", label: "Refund request arrives", detail: "A customer messages about a refund." },
    { kind: "agent", label: "Collect order details", detail: "Asks for the order number and reason." },
    { kind: "approval", label: "Owner approves", detail: "Nothing is refunded without a yes." },
    { kind: "output", label: "Customer told the outcome", detail: "Reply sent after approval." },
  ],
  channels: ["whatsapp", "WEB", "CARRIER_PIGEON"],
  trigger: "CHANNEL",
  secrets: [{ name: "shopify api key", why: "read orders" }, { name: "x", why: "bad" }],
  tools: [{ name: "Order lookup", does: "Find an order by number" }],
};

describe("extractJson", () => {
  it("reads fenced and prose-wrapped replies", () => {
    expect(extractJson('```json\n{"a":1}\n```')).toEqual({ a: 1 });
    expect(extractJson('Sure! Here you go: {"a":1} hope it helps')).toEqual({ a: 1 });
  });
  it("returns null for junk", () => {
    expect(extractJson("no json here")).toBeNull();
    expect(extractJson("{not json}")).toBeNull();
  });
});

describe("blueprintFromAiDraft (agent)", () => {
  const bp = blueprintFromAiDraft(GOAL, REPLY, "agent")!;

  it("uses the AI-written instructions, not the pasted goal", () => {
    expect(composeInstructions(bp)).toContain("You are the refund desk");
    expect(bp.sources["agent.instructions"]).toBe("cloud-ai");
    expect(bp.agent.name).toBe("Refund Desk Agent");
    expect(bp.agent.autonomyTier).toBe("SUPERVISED");
    expect(bp.agent.temperature).toBe(0.2);
    expect(bp.agent.knowledgeBase).toBe(true);
  });

  it("takes the drafted workflow steps", () => {
    expect(bp.workflow?.steps.map((s) => s.kind)).toEqual(["trigger", "agent", "approval", "output"]);
    expect(bp.sources["workflow.steps"]).toBe("cloud-ai");
  });

  it("filters wiring to closed lists and valid secret names", () => {
    expect(bp.wiring.channels).toEqual(["WHATSAPP", "WEB"]);
    expect(bp.wiring.trigger).toBe("CHANNEL");
    expect(bp.wiring.secretsText).toBe("SHOPIFY_API_KEY - read orders");
    expect(bp.wiring.toolsText).toBe("Order lookup - Find an order by number");
  });

  it("produces a blueprint that can be built", () => {
    expect(validate(bp).errors).toEqual([]);
  });

  it("keeps recipe instructions when the model's are too short", () => {
    const weak = blueprintFromAiDraft(GOAL, { ...REPLY, instructions: "Be nice." }, "agent")!;
    expect(composeInstructions(weak)).not.toContain("Be nice.");
  });

  it("falls back to manual when a schedule has no usable cron", () => {
    const b = blueprintFromAiDraft(GOAL, { ...REPLY, trigger: "SCHEDULE", cron: "daily" }, "agent")!;
    expect(b.wiring.trigger).toBe("MANUAL");
    expect(validate(b).errors).toEqual([]);
  });

  it("ignores a workflow that does not start with a trigger", () => {
    const b = blueprintFromAiDraft(GOAL, { ...REPLY, steps: REPLY.steps.slice(1) }, "agent")!;
    expect(b.sources["workflow.steps"]).not.toBe("cloud-ai");
  });

  it("rejects non-objects", () => {
    expect(blueprintFromAiDraft(GOAL, "nope", "agent")).toBeNull();
  });
});

describe("blueprintFromAiDraft (app)", () => {
  it("fills the app brief and validates template and colour", () => {
    const bp = blueprintFromAiDraft(
      "a booking site for my salon",
      { name: "Glow Salon", templateId: "astro-site", tagline: "Book in a minute", accent: "#c0507a", features: ["Service menu", "Booking form", ""] },
      "app"
    )!;
    expect(bp.app).toMatchObject({ name: "Glow Salon", templateId: "astro-site", accent: "#c0507a", features: "Service menu\nBooking form" });
    const bad = blueprintFromAiDraft("a team tool", { templateId: "rust-wasm", accent: "red" }, "app")!;
    expect(bad.app.templateId).toBe("node-react");
    expect(bad.app.accent).toBe("#6d5df2");
  });
});
