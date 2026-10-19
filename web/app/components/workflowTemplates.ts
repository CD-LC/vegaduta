import { newNodeId, nodeDef } from "./workflow/nodeDefs";
import type { WorkflowGraphEdge, WorkflowGraphNode } from "./workflow/nodeDefs";
import type { WorkflowTemplate, WorkflowTemplateCategory } from "./templates-data/workflows/types";


// Workflow template gallery - the workflow-side counterpart to
// agentTemplates.ts. Same posture: picking a template only pre-fills the
// existing create-workflow form (name, description, starter graph) - it
// never auto-submits, so the person always reviews/edits the canvas before
// anything actually runs. Unlike the industry recipes under
// /help/workflow-recipes (documentation you manually recreate), applying one
// of these actually creates a real, editable workflow with a real starter
// graph wired up on the canvas.
//
// i18n note (mirrors agentTemplates.ts): `name`/`tagline` are UI chrome, so
// they're looked up from the "workflowTemplates" en.json namespace via the
// key-builder helpers below, not stored here as literal English. `steps[].label`
// and `description` ARE stored here as literal English, same reasoning as
// agentTemplates.ts's `systemPrompt` exception - they become the actual node
// labels and workflow description of the created workflow, not just gallery
// display text, so translating them would change the created object per
// locale rather than just the gallery's display language.
//
// The catalogue itself is DATA: one file per category under
// ./templates-data/workflows (validated by registry.test.ts there). This
// module keeps the original import path, the key builders and the graph
// builder.
export * from "./templates-data/workflows/types";
export { WORKFLOW_TEMPLATES, WORKFLOW_TEMPLATES_BY_CATEGORY } from "./templates-data/workflows/registry";

const CATEGORY_KEYS: Record<WorkflowTemplateCategory, string> = {
  "E-commerce & retail": "categoryEcommerceRetail",
  "Healthcare & clinics": "categoryHealthcareClinics",
  "Real estate": "categoryRealEstate",
  "Hospitality & restaurants": "categoryHospitalityRestaurants",
  "Legal services": "categoryLegalServices",
  "Logistics & delivery": "categoryLogisticsDelivery",
  "Content & media": "categoryContentMedia",
  "Orchestration & integrations": "categoryOrchestrationIntegrations",
  "Small business": "categorySmallBusiness",
  "Sales & marketing": "categorySalesMarketing",
  "Customer support": "categoryCustomerSupport",
  "People & finance": "categoryPeopleFinance",
  "Data & reporting": "categoryDataReporting",
  "Engineering & modernization": "categoryEngineeringModernization",
};
export function categoryLabelKey(category: WorkflowTemplateCategory) {
  return CATEGORY_KEYS[category] || category;
}

function templateKeyBase(key: string) {
  return key.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase());
}

export function templateNameKey(key: string) {
  return `${templateKeyBase(key)}Name`;
}

export function templateTaglineKey(key: string) {
  return `${templateKeyBase(key)}Tagline`;
}

// Node types that need a person to pick an agent on the canvas before the
// workflow can be activated (OrchestratorService#validate rejects a blank
// agentId). Everything else a template ships is runnable as-is - or, for the
// http/bulk_email-group nodes, needs a URL/contact group (see templateSetup).
const AGENT_SLOT_TYPES = new Set(["agent", "tool", "supervisor"]);

/** How many agent slots the person must fill on the canvas (0 = nothing to assign). */
export function templateAgentSlots(template: WorkflowTemplate): number {
  return buildWorkflowGraph(template).nodes.filter((n) => AGENT_SLOT_TYPES.has(n.type)).length;
}

/** Node count excluding the trigger - the "steps" the card advertises. */
export function templateStepCount(template: WorkflowTemplate): number {
  return buildWorkflowGraph(template).nodes.filter((n) => n.type !== "trigger").length;
}

/** The handful surfaced on the /workflows page itself: the ones that run with no agent to assign first. */
export const FEATURED_TEMPLATE_KEYS = [
  "lead-capture-crm-workflow",
  "support-ticket-triage-workflow",
  "resume-screening-workflow",
  "faq-human-handoff-workflow",
  "refund-approval-threshold-workflow",
  "deep-research-workflow",
] as const;

// Builds a real, editable starter graph for a template: the template's own
// buildGraph when it has one (non-linear shapes), otherwise Trigger -> each
// of `steps` chained in order. Mirrors starterGraph() in workflow/nodeDefs.ts
// (a lone Trigger node) but pre-wires the rest of the flow so applying a
// template lands on the canvas with something concrete already built, not
// just a blank slate - same "concrete starting point, not a blank textarea"
// reasoning as agentTemplates.ts.
export function buildWorkflowGraph(template: WorkflowTemplate): {
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
} {
  if (template.buildGraph) return template.buildGraph();
  const triggerId = newNodeId("trigger");
  const nodes: WorkflowGraphNode[] = [
    {
      id: triggerId,
      type: "trigger",
      position: { x: 80, y: 160 },
      data: { label: "Trigger", config: { channel: template.triggerChannel || null } },
    },
  ];
  const edges: WorkflowGraphEdge[] = [];
  let prevId = triggerId;
  (template.steps ?? []).forEach((step, index) => {
    const id = newNodeId(step.type);
    nodes.push({
      id,
      type: step.type,
      position: { x: 80 + (index + 1) * 260, y: 160 },
      data: { label: step.label, config: { ...nodeDef(step.type).defaultConfig, ...(step.config || {}) } },
    });
    edges.push({ id: `e-${prevId}-x-${id}`, source: prevId, target: id, sourceHandle: null, targetHandle: null });
    prevId = id;
  });
  return { nodes, edges };
}
