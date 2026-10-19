import { newNodeId, nodeDef } from "../../workflow/nodeDefs";
import type { WorkflowGraphEdge, WorkflowGraphNode, WorkflowNodeConfig } from "../../workflow/nodeDefs";

// Node/edge builders shared by the non-linear template graphs below. Node
// ids are minted per call (same as the linear builder's) so applying a
// template twice never produces two graphs sharing ids.
export function makeNode(
  type: string,
  x: number,
  y: number,
  label: string,
  config: WorkflowNodeConfig = {}
): WorkflowGraphNode {
  return {
    id: newNodeId(type),
    type,
    position: { x, y },
    data: { label, config: { ...nodeDef(type).defaultConfig, ...config } },
  };
}

export function makeEdge(source: string, target: string, sourceHandle: string | null = null): WorkflowGraphEdge {
  return { id: `e-${source}-x-${target}`, source, target, sourceHandle, targetHandle: null };
}

// ---- Wave 4 builders --------------------------------------------------------
// Conventions shared by the graphs below (each verified against core's
// OrchestratorService#validate and the node handlers):
//  * question_classifier / supervisor / switch route by matching the edge's
//    sourceHandle to the picked class handle / route / case name; a Switch's
//    fallthrough port is literally "default"; a Condition's ports are "true"
//    and "false".
//  * parameter_extractor and document_extractor REPLACE the run's data with
//    their JSON output, so every node after them reads $json.<field>.
//  * question_classifier is passthrough by default: the branch it picks still
//    receives the text it classified.

export type TemplateGraph = { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] };

// Edges for a straight run of nodes, in the order given.
export function chainEdges(...nodes: WorkflowGraphNode[]): WorkflowGraphEdge[] {
  return nodes.slice(1).map((node, i) => makeEdge(nodes[i].id, node.id));
}

export function extractor(
  x: number,
  y: number,
  label: string,
  parameters: AiParamSpec[],
  instruction = ""
): WorkflowGraphNode {
  return makeNode("parameter_extractor", x, y, label, { parameters, instruction });
}

export type AiParamSpec = {
  name: string;
  type: "string" | "number" | "boolean" | "array" | "enum";
  description: string;
  required: boolean;
  options?: string[];
};

export function classifier(
  x: number,
  y: number,
  label: string,
  classes: { handle: string; description: string; label?: string }[],
  defaultClass: string,
  instruction = ""
): WorkflowGraphNode {
  return makeNode("question_classifier", x, y, label, { classes, defaultClass, instruction });
}
