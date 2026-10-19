import { describe, expect, it } from "vitest";
import {
  WORKFLOW_TEMPLATES,
  WORKFLOW_TEMPLATE_CATEGORIES,
  buildWorkflowGraph,
  categoryLabelKey,
  templateNameKey,
  templateTaglineKey,
} from "./workflowTemplates";
import { AGENT_TEMPLATES } from "./agentTemplates";
import en from "../../messages/en.json";

const strings = (en as Record<string, any>).workflowTemplates as Record<string, string>;
const NEW_KEYS = ["daily-briefing-workflow", "whatsapp-reply-with-approval-workflow"];

const find = (key: string) => {
  const t = WORKFLOW_TEMPLATES.find((x) => x.key === key);
  if (!t) throw new Error(`no workflow template ${key}`);
  return t;
};

describe("small-business workflow templates", () => {
  it("exist, have unique keys, and sit in a listed category with English strings", () => {
    expect(strings).toBeTruthy();
    const keys = WORKFLOW_TEMPLATES.map((t) => t.key);
    expect(new Set(keys).size).toBe(keys.length);
    expect(WORKFLOW_TEMPLATE_CATEGORIES as readonly string[]).toContain("Small business");
    expect(strings[categoryLabelKey("Small business")]).toBeTruthy();
    for (const key of NEW_KEYS) {
      const t = find(key);
      expect(t.category).toBe("Small business");
      expect(t.description.length).toBeGreaterThan(40);
      expect(strings[templateNameKey(key)], `${key} name`).toBeTruthy();
      expect(strings[templateTaglineKey(key)], `${key} tagline`).toBeTruthy();
      expect(t.description + strings[templateTaglineKey(key)]).not.toMatch(/[$₹]\s?\d|\bRs\.?\s?\d/i);
    }
  });

  it("the WhatsApp reply workflow holds every reply for approval before anything is sent", () => {
    const t = find("whatsapp-reply-with-approval-workflow");
    expect(t.triggerChannel).toBe("WHATSAPP");
    const { nodes, edges } = buildWorkflowGraph(t);
    const order = nodes.map((n) => n.type);
    expect(order).toEqual(["trigger", "agent", "approval", "output"]);
    expect(nodes[3].data.config?.channel).toBe("whatsapp");
    // Edges chain the nodes in that order: the approval gate cannot be bypassed.
    for (let i = 0; i < nodes.length - 1; i++) {
      expect(edges.some((e) => e.source === nodes[i].id && e.target === nodes[i + 1].id), `edge ${i}`).toBe(true);
    }
    expect(edges).toHaveLength(nodes.length - 1);
    // The description must state both honest prerequisites.
    expect(t.description).toMatch(/approve/i);
    expect(t.description).toMatch(/WhatsApp channel/);
  });

  it("the daily briefing workflow keeps the result in the run history - no delivery channel by default", () => {
    const t = find("daily-briefing-workflow");
    const { nodes } = buildWorkflowGraph(t);
    expect(nodes.map((n) => n.type)).toEqual(["trigger", "agent", "output"]);
    expect(nodes[2].data.config?.channel).toBe("log");
    // The schedule is set on the workflow after creation, so the card says so.
    expect(t.description).toMatch(/schedule/i);
    expect(t.description).toMatch(/channel only if/i);
  });

  it("each pairs with an agent template that exists", () => {
    const agentKeys = new Set<string>(AGENT_TEMPLATES.map((a) => a.key));
    expect(agentKeys.has("daily-briefing-writer")).toBe(true);
    expect(agentKeys.has("whatsapp-reply-drafter")).toBe(true);
  });
});
