// Shared data shapes for the node config drawer and its per-node forms.

import type { HttpHeaderPair, HumanInputField, VoiceMenuBranch, BulkEmailSource, SupervisorRoute as CanonicalSupervisorRoute } from "../nodeDefs";

// Shape of the scoped t() function useTranslations returns (lib/i18n.tsx
// deliberately keeps its own signature loose for next-intl drop-in parity,
// so this local alias is the typed contract this file's helpers rely on:
// key in, rendered string out, optional {placeholder} values).
export type Translator = (key: string, values?: Record<string, string | number>) => string;

// ---------------------------------------------------------------------------
// Data shapes. api.ts's listAgents/listCollections/listDevices/listWorkflows/
// getRlPolicies/listAvailableTools responses are untyped (Promise<any>) as of
// the noImplicitAny pass, so these interfaces describe exactly the subset of
// fields THIS drawer actually reads - deliberately narrow rather than full
// server DTOs, so they can only ever under-promise. If api.ts grows real
// response types for these endpoints later, swap these for imports.
// ---------------------------------------------------------------------------

// From api.listAgents (passed down by WorkflowCanvas). The two knowledge
// fields are the Knowledge Stage F1 allow-list pair AgentStructuredInputConfig
// uses to scope its collection multi-select.
export interface AgentSummary {
  id: string;
  name: string;
  knowledgeBaseEnabled?: boolean;
  allowedKnowledgeCollectionIds?: string[] | null;
}

// From api.listCollections (passed down by WorkflowCanvas).
export interface CollectionSummary {
  id: string;
  name: string;
}

// From api.listDevices (passed down by WorkflowCanvas). `revoked` devices are
// filtered out of the device_control picker - a command can't be sent to a
// device whose pairing was revoked.
export interface DeviceSummary {
  id: string;
  deviceName: string;
  platform: string;
  revoked?: boolean;
}

// From api.listWorkflows - the Trigger Workflow node only needs id (for the
// self-reference filter) and name (for the dropdown label).
export interface WorkflowSummary {
  id: string;
  name: string;
}

// One entry of api.listAvailableTools - the tool picker only ever renders the
// name (it doubles as both value and label, matching AgentsPanel's editor).
export interface AvailableTool {
  name: string;
}

// One row of api.getRlPolicies / the api.updateRlPolicy response - mirrors
// RlController's policy DTO (dimension + the three knobs the toggle round-trips).
export interface RlPolicy {
  dimension: string;
  enabled: boolean;
  explorationRate: number;
  algorithm?: string | null;
}

// api.getNodeWebhook / enable/disableNodeWebhook response (the server-managed
// secret half of the trigger split described on TriggerNodeConfig below).
export interface NodeWebhookState {
  enabled?: boolean;
  token?: string | null;
  url?: string | null;
}

// One `{handle, label}` output-port entry of the Supervisor node's `routes`
// list - see SupervisorNodeConfig's own doc comment for why handle text is
// load-bearing (it's what drawn edges' sourceHandle resolves against).
// Partial over nodeDefs' canonical SupervisorRoute rather than a second
// hand-written shape: the drawer parses routes out of stored JSON where
// either key may be missing, but deriving keeps the two from drifting.
export type SupervisorRoute = Partial<CanonicalSupervisorRoute>;

// The fixed structured-input field set AgentSchemaService generates
// server-side (see AgentStructuredInputConfig's doc comment) - snake_case on
// purpose, these keys are the server contract, not frontend naming.
export interface AgentInputMapping {
  query?: string;
  context_data?: string;
  knowledge_collection_ids?: string[];
}

// Union of every per-node-type config key this drawer can write. One shared
// bag rather than a per-node-type discriminated union because that's what the
// runtime genuinely is: node.data.config is a single JSON object that
// round-trips through the saved graph, and WorkflowNodeExecutor reads only
// the keys relevant to each node type, ignoring leftovers from a node whose
// type was switched. All-optional for the same reason - an untouched node's
// config can legitimately be {} (or just {agentId}, see the backward-compat
// note on AgentStructuredInputConfig).
export interface WorkflowNodeConfig {
  // trigger
  channel?: string;
  webhookAuthMode?: string;
  // "form" = a hosted-form trigger (FormTriggerEditor); the form schema itself is edited there, not typed here.
  triggerType?: string;
  // agent
  agentId?: string;
  inputMapping?: AgentInputMapping;
  outputMapping?: string;
  requiresApproval?: boolean;
  // knowledge
  collectionId?: string;
  topK?: number;
  // condition
  contains?: string;
  // condition: "contains" (default) or "expression" (bare boolean expression, no braces)
  mode?: string;
  expression?: string;
  // loop
  itemsField?: string;
  maxIterations?: number;
  // fan_out (shares itemsField with loop)
  maxItems?: number;
  maxConcurrency?: number;
  // output
  fileType?: string;
  fileName?: string;
  emailTo?: string;
  telegramChatId?: string;
  whatsappTo?: string;
  whatsappMessage?: string;
  whatsappTemplate?: { name?: string; language?: string } | null;
  whatsappTemplateParams?: string[];
  driveFolderId?: string;
  driveFileName?: string;
  // device_control
  deviceId?: string;
  commandType?: string;
  payload?: string;
  // approval
  message?: string;
  adminOnly?: boolean;
  formFields?: string[];
  // tool
  toolName?: string;
  argsJson?: string;
  // supervisor
  routes?: SupervisorRoute[];
  instructions?: string;
  // workflow (trigger-another-workflow)
  targetWorkflowId?: string;
  // code
  operation?: CodeOperation;
  field?: string;
  pattern?: string;
  replacement?: string;
  template?: string;
  // http (HTTP Request) - mirrors nodeDefs.ts's WorkflowNodeConfig http keys
  method?: string;
  url?: string;
  headers?: HttpHeaderPair[];
  query?: HttpHeaderPair[];
  bodyType?: string;
  // `body` is overloaded by backend contract: a request-body STRING on
  // http nodes, the embedded {nodes, edges} sub-graph OBJECT on sub_flow
  // nodes (the engine reads config "body" verbatim in both cases). Each
  // render site narrows it - http coerces to string, sub_flow to object.
  body?: string | { nodes: any[]; edges: any[] };
  saveAs?: string;
  timeoutSeconds?: number;
  failOnHttpError?: boolean;
  // human_input (Tier-3) - the form schema snapshotted into the
  // workflow_input_request row; shape mirrors nodeDefs.ts's
  // HumanInputField exactly.
  fields?: HumanInputField[];
  // sub_flow (Tier-3) - `entryNodeId` overrides which body node starts
  // (blank = the body's single no-incoming-edge node).
  entryNodeId?: string;
  // voice_menu - mirrors nodeDefs.ts's WorkflowNodeConfig voice_menu keys
  // (timeoutSeconds above is shared with http).
  prompt?: string;
  invalidPrompt?: string;
  numDigits?: number;
  finishOnKey?: string;
  maxRetries?: number;
  branches?: VoiceMenuBranch[];
  // bulk_email - mirrors nodeDefs.ts's WorkflowNodeConfig bulk_email keys
  // (`body` above is shared with http; itemsField with loop/fan_out).
  recipientsSource?: BulkEmailSource;
  emailField?: string;
  groupId?: string;
  staticRecipients?: string;
  subject?: string;
  html?: boolean;
  replyTo?: string;
  maxPerMinute?: number;
  maxRecipients?: number;
  skipIfReplied?: boolean;
  includeUnsubscribe?: boolean;
  // crm_lead - mirrors nodeDefs.ts (all strings, templated server-side).
  action?: string;
  leadId?: string;
  email?: string;
  name?: string;
  phone?: string;
  company?: string;
  title?: string;
  source?: string;
  stage?: string;
  score?: string;
  value?: string;
  tags?: string;
  notes?: string;
  note?: string;
  owner?: string;
  activityType?: string;
  activityBody?: string;
  // Per-node resilience (Tier-3) - see the retry field's own comment at
  // its render site below.
  retryAttempts?: number;
}

// The patch shape onChange accepts - WorkflowCanvas's updateNode merges this
// into the node's `data` (label and/or a whole replacement config object).
export interface NodeDataPatch {
  label?: string;
  config?: WorkflowNodeConfig;
}

// The slice of an @xyflow/react node this drawer reads. WorkflowCanvas keeps
// its node array untyped, so this is the drawer-side contract: identity for
// onChange/onDelete, `type` for the per-node-type sections, and the
// label/config pair living under `data`.
export interface DrawerNode {
  id: string;
  type: string;
  data: {
    label?: string;
    config?: WorkflowNodeConfig;
  };
}

export interface NodeConfigDrawerProps {
  node: DrawerNode | null;
  workflowId: string;
  agents: AgentSummary[] | null;
  collections: CollectionSummary[] | null;
  devices: DeviceSummary[] | null;
  triggerCount: number;
  onChange: (nodeId: string, patch: NodeDataPatch) => void;
  onDelete: (nodeId: string) => void;
  onClose: () => void;
}

export const CODE_OPERATION_VALUES = [
  "extract",
  "regex_replace",
  "template",
  "uppercase",
  "lowercase",
  "json_parse",
  "json_stringify",
] as const;
// `as const` above exists so this union can be derived instead of hand-listed
// twice - it's also what WorkflowNodeConfig.operation is typed as, keeping
// the label map lookup below exhaustively checked against the value list.
export type CodeOperation = (typeof CODE_OPERATION_VALUES)[number];
