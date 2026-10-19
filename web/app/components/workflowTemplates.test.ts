import { describe, expect, it } from "vitest";
import {
  WORKFLOW_TEMPLATES,
  WORKFLOW_TEMPLATE_CATEGORIES,
  buildWorkflowGraph,
  categoryLabelKey,
  templateNameKey,
  templateTaglineKey,
} from "./workflowTemplates";
import { NODE_DEFS } from "./workflow/nodeDefs";
import en from "../../messages/en.json";

const strings = (en as Record<string, any>).workflowTemplates as Record<string, string>;

// Mirrors OrchestratorService#validateNodeConfig's output allow-list and
// WorkflowTriggerChannel. Two gallery templates once shipped "sms"/"webhook"
// output channels, which the server rejects on every activation.
const OUTPUT_CHANNELS = new Set(["log", "event", "file", "email", "telegram", "teams", "whatsapp", "google_drive"]);
const TRIGGER_CHANNELS = new Set(["WEBHOOK", "WHATSAPP", "CALL", "SMS", "MESSENGER", "INSTAGRAM", "SLACK", "DISCORD", "X", "EMAIL"]);
const KNOWN_TYPES = new Set(NODE_DEFS.map((d) => d.type));

// Mirrors the save/activation-time rules OrchestratorService#validate
// enforces server-side, applied to every gallery template's starter graph.
// A template that fails these checks can be CREATED (create does not
// validate) but can never be ACTIVATED - which would make it a dead
// template, so the gallery itself is held to the same bar here.
describe("workflow template starter graphs", () => {
  it("every template produces a graph with unique node ids and resolvable edges", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      const ids = nodes.map((n) => n.id);
      expect(new Set(ids).size, `${template.key}: duplicate node ids`).toBe(ids.length);
      const idSet = new Set(ids);
      for (const edge of edges) {
        expect(idSet.has(edge.source), `${template.key}: edge source ${edge.source} missing`).toBe(true);
        expect(idSet.has(edge.target), `${template.key}: edge target ${edge.target} missing`).toBe(true);
      }
      // "At least one trigger" is the validator's baseline requirement.
      expect(
        nodes.some((n) => n.type === "trigger"),
        `${template.key}: no trigger node`
      ).toBe(true);
    }
  });

  it("parallel nodes fan out to 1-10 branches", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      for (const node of nodes.filter((n) => n.type === "parallel")) {
        const out = edges.filter((e) => e.source === node.id).length;
        expect(out, `${template.key}: parallel node needs 1-10 branches`).toBeGreaterThanOrEqual(1);
        expect(out, `${template.key}: parallel node needs 1-10 branches`).toBeLessThanOrEqual(10);
      }
    }
  });

  it("fan_out nodes have exactly one outgoing edge to the worker", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      for (const node of nodes.filter((n) => n.type === "fan_out")) {
        const out = edges.filter((e) => e.source === node.id).length;
        expect(out, `${template.key}: fan_out needs exactly one outgoing edge`).toBe(1);
      }
    }
  });

  it("supervisor routes match edge sourceHandles", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      for (const node of nodes.filter((n) => n.type === "supervisor")) {
        const routes = (node.data.config?.routes ?? []).map((r) => r.handle);
        expect(routes.length, `${template.key}: supervisor needs usable routes`).toBeGreaterThan(0);
        const handles = edges.filter((e) => e.source === node.id).map((e) => e.sourceHandle);
        expect(handles.sort(), `${template.key}: supervisor edges must carry route handles`).toEqual(
          [...routes].sort()
        );
      }
    }
  });

  it("the graph is acyclic", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      const adjacency = new Map<string, string[]>();
      for (const e of edges) adjacency.set(e.source, [...(adjacency.get(e.source) ?? []), e.target]);
      const visiting = new Set<string>();
      const done = new Set<string>();
      const visit = (id: string): boolean => {
        if (done.has(id)) return false;
        if (visiting.has(id)) return true;
        visiting.add(id);
        const cycle = (adjacency.get(id) ?? []).some(visit);
        visiting.delete(id);
        done.add(id);
        return cycle;
      };
      for (const n of nodes) {
        expect(visit(n.id), `${template.key}: cycle through ${n.id}`).toBe(false);
      }
    }
  });

  it("uses only node types the canvas knows (nodeDef() silently falls back to Trigger otherwise)", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes } = buildWorkflowGraph(template);
      for (const n of nodes) expect(KNOWN_TYPES.has(n.type), `${template.key}: unknown node type ${n.type}`).toBe(true);
    }
  });

  it("every trigger and output channel is one the server accepts", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      expect(TRIGGER_CHANNELS.has(template.triggerChannel), `${template.key}: trigger channel`).toBe(true);
      const { nodes } = buildWorkflowGraph(template);
      for (const n of nodes.filter((x) => x.type === "trigger")) {
        expect(TRIGGER_CHANNELS.has(String(n.data.config?.channel)), `${template.key}: trigger node channel`).toBe(true);
      }
      for (const n of nodes.filter((x) => x.type === "output")) {
        expect(OUTPUT_CHANNELS.has(String(n.data.config?.channel)), `${template.key}: output channel ${n.data.config?.channel}`).toBe(true);
      }
    }
  });

  it("every template and category has English gallery strings", () => {
    for (const category of WORKFLOW_TEMPLATE_CATEGORIES) {
      expect(strings[categoryLabelKey(category)], `category ${category}`).toBe(category);
    }
    for (const template of WORKFLOW_TEMPLATES) {
      expect(WORKFLOW_TEMPLATE_CATEGORIES as readonly string[]).toContain(template.category);
      expect(strings[templateNameKey(template.key)], `${template.key} name`).toBeTruthy();
      expect(strings[templateTaglineKey(template.key)], `${template.key} tagline`).toBeTruthy();
    }
  });

  it("question classifier edges carry class handles and every class is wired", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      for (const node of nodes.filter((n) => n.type === "question_classifier")) {
        const classes = ((node.data.config?.classes ?? []) as { handle: string }[]).map((c) => c.handle);
        expect(classes.length, `${template.key}: classifier needs >= 2 classes`).toBeGreaterThanOrEqual(2);
        const handles = edges.filter((e) => e.source === node.id).map((e) => e.sourceHandle);
        expect([...handles].sort(), `${template.key}: classifier edges must match class handles`).toEqual([...classes].sort());
        const dflt = String(node.data.config?.defaultClass ?? "");
        if (dflt) expect(classes, `${template.key}: defaultClass must be a class`).toContain(dflt);
      }
    }
  });

  it("switch edges use a case name or default; condition edges use true/false", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      for (const node of nodes.filter((n) => n.type === "switch")) {
        const ports = new Set(
          [...((node.data.config?.cases ?? []) as { name: string }[]).map((c) => c.name.toLowerCase()), "default"]
        );
        for (const e of edges.filter((x) => x.source === node.id)) {
          expect(ports.has(String(e.sourceHandle)), `${template.key}: switch edge handle ${e.sourceHandle}`).toBe(true);
        }
      }
      for (const node of nodes.filter((n) => n.type === "condition")) {
        for (const e of edges.filter((x) => x.source === node.id)) {
          expect(["true", "false"], `${template.key}: condition edge handle`).toContain(String(e.sourceHandle));
        }
        if (node.data.config?.mode === "expression") {
          expect(String(node.data.config?.expression ?? "").trim(), `${template.key}: expression`).not.toBe("");
        }
      }
    }
  });

  it("data, email and extractor nodes ship their structurally required config", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes } = buildWorkflowGraph(template);
      for (const n of nodes) {
        const c = (n.data.config ?? {}) as Record<string, any>;
        if (n.type === "bulk_email") {
          expect(String(c.subject ?? "").trim(), `${template.key}: bulk_email subject`).not.toBe("");
          expect(String(c.body ?? "").trim(), `${template.key}: bulk_email body`).not.toBe("");
        }
        if (n.type === "filter") expect(c.conditions?.length, `${template.key}: filter conditions`).toBeGreaterThan(0);
        if (n.type === "aggregate") expect(c.aggregations?.length, `${template.key}: aggregations`).toBeGreaterThan(0);
        if (n.type === "limit") expect(String(c.count ?? "").trim(), `${template.key}: limit count`).not.toBe("");
        if (n.type === "parameter_extractor") {
          const params = (c.parameters ?? []) as { name: string; type: string; options?: string[] }[];
          expect(params.length, `${template.key}: extractor params`).toBeGreaterThan(0);
          for (const prm of params) {
            expect(prm.name, `${template.key}: extractor param name`).toMatch(/^[A-Za-z][A-Za-z0-9_]{0,63}$/);
            if (prm.type === "enum") expect(prm.options?.length, `${template.key}: enum options`).toBeGreaterThan(0);
          }
        }
      }
    }
  });

  it("no template depends on Wait nodes or form triggers (both off by default on a deployment)", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes } = buildWorkflowGraph(template);
      expect(nodes.some((n) => n.type === "wait"), `${template.key}: wait node`).toBe(false);
      expect(nodes.some((n) => n.data.config?.triggerType === "form"), `${template.key}: form trigger`).toBe(false);
    }
  });

  it("every non-trigger node is reachable from a trigger", () => {
    for (const template of WORKFLOW_TEMPLATES) {
      const { nodes, edges } = buildWorkflowGraph(template);
      const seen = new Set(nodes.filter((n) => n.type === "trigger").map((n) => n.id));
      const stack = [...seen];
      while (stack.length) {
        const id = stack.pop()!;
        for (const e of edges.filter((x) => x.source === id)) if (!seen.has(e.target)) { seen.add(e.target); stack.push(e.target); }
      }
      for (const n of nodes) expect(seen.has(n.id), `${template.key}: orphan node ${n.data.label}`).toBe(true);
    }
  });
});
