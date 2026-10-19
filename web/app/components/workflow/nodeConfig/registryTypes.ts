import type { AgentSummary, CollectionSummary, DeviceSummary, WorkflowNodeConfig } from "./types";

/** Props every registered per-node form receives. Forms read only what they need. */
export interface NodeFormProps {
  nodeId: string;
  nodeType: string;
  workflowId: string;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
  agents: AgentSummary[] | null;
  collections: CollectionSummary[] | null;
  devices: DeviceSummary[] | null;
}
