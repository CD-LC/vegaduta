import type { LucideIcon } from "lucide-react";
import type { WorkflowGraphEdge, WorkflowGraphNode, WorkflowNodeConfig } from "../../workflow/nodeDefs";

export const WORKFLOW_TEMPLATE_CATEGORIES = [
  "E-commerce & retail",
  "Healthcare & clinics",
  "Real estate",
  "Hospitality & restaurants",
  "Legal services",
  "Logistics & delivery",
  "Content & media",
  "Orchestration & integrations",
  "Small business",
  "Sales & marketing",
  "Customer support",
  "People & finance",
  "Data & reporting",
  // Engineering shelf (2026-10-10): mainframe abends, SAP transports, COBOL
  // copybooks, payment reconciliation, pipeline migration and release gates -
  // the processes an enterprise platform team actually automates. Additive.
  "Engineering & modernization",
] as const;

export type WorkflowTemplateCategory = (typeof WORKFLOW_TEMPLATE_CATEGORIES)[number];

// One linear step in a template's chain (Trigger -> step -> step -> ...).
export type WorkflowTemplateStep = {
  type: string;
  label: string;
  config?: WorkflowNodeConfig;
};

// The template shape. `steps` is the simple case - buildWorkflowGraph chains
// it linearly. `buildGraph` is the escape hatch for starter graphs that are
// NOT a straight line (the orchestration templates below: parallel's
// split-and-rejoin shape, fan_out's single-worker loop). A template must
// provide exactly one of the two.
export interface WorkflowTemplate {
  key: string;
  category: WorkflowTemplateCategory;
  icon: LucideIcon;
  description: string;
  triggerChannel: string;
  steps?: WorkflowTemplateStep[];
  buildGraph?: () => { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] };
}
