// Config and graph types of the workflow node taxonomy (the palette entries
// themselves live in nodeDefCatalog.ts). Re-exported through ./nodeDefs.
import type { LucideIcon } from "lucide-react";
import type { AggregateSpec, DataCondition, SetAssignment, SortKey, SwitchCase } from "./dataNodes";
import type { AiClassDef, AiParamDef } from "./aiNodes";
import type { DataTableFilterRow } from "../../lib/dataTables";

// One named Supervisor output route ({handle, label}) - see the supervisor
// entry's comment in nodeDefCatalog.ts for how `handle` doubles as the edge's sourceHandle.
export type SupervisorRoute = { handle: string; label: string };

// One HTTP Request node header or query-string pair. `value` may be a vault
// reference ("{{secret.NAME}}") - resolved server-side inside
// HttpRequestExecutor, never here, and never stored as plaintext.
export type HttpHeaderPair = { name: string; value: string };
export const HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"] as const;
export const HTTP_BODY_TYPES = ["none", "json", "text", "form"] as const;

// One field of a Human Input node's form schema - the exact
// [{key,label,type,required,options?}] shape WorkflowInputRequestService
// snapshots into workflow_input_request.form_schema_json and validates
// submits against. `options` only matters when type === "select".
export type HumanInputField = {
  key: string;
  label: string;
  type: "text" | "number" | "select" | "boolean";
  required: boolean;
  options?: string[];
};

// One option of a Voice Menu node's `branches` list - the exact
// [{digit, label}] shape WorkflowVoiceMenuRequestService snapshots and
// matches the caller's pressed digits against. `label` doubles as this
// option's output-port id on the canvas (WorkflowNode.tsx), i.e. the edge's
// sourceHandle, which is what WorkflowExecutionEngine#nextNodeId matches the
// node's resolved branch against - same mechanism as SupervisorRoute.handle.
export type VoiceMenuBranch = { digit: string; label: string };

// Union of every per-type defaultConfig field (nodeDefCatalog.ts), all optional because a
// node only ever carries its own type's keys (a single discriminated union
// per node type would be the stricter model, but every consumer -
// summarizeConfig in WorkflowNode.tsx, NodeConfigDrawer's per-type sections -
// already switches on `type` before reading a field, so optional keys match
// how the data is actually read without forcing a cast at each switch arm).
export type WorkflowNodeConfig = {
  channel?: string | null;
  // output node destinations/content (expression-resolved at run time; blank whatsappTo replies to the WhatsApp sender)
  emailTo?: string;
  whatsappTo?: string;
  whatsappMessage?: string;
  fileType?: string;
  fileName?: string;
  agentId?: string;
  collectionId?: string;
  topK?: number;
  contains?: string;
  // condition: "contains" (default, the text match) or "expression" (boolean expression, no braces).
  mode?: string;
  expression?: string;
  message?: string;
  adminOnly?: boolean;
  formFields?: string[];
  // human_input - the structured form schema snapshotted into the
  // workflow_input_request row when the run pauses AWAITING_INPUT.
  fields?: HumanInputField[];
  // sub_flow - the embedded body graph ({nodes, edges} in the same wire
  // shape as the outer graph) the engine walks once per resolved item,
  // plus the entry-node override and the same itemsField/maxIterations
  // contract LOOP uses. `body` is a union below - it shares the config
  // key with http's string request body (the backend reads "body"
  // verbatim for both node types).
  entryNodeId?: string;
  // Per-node resilience (Tier-3): retryAttempts overrides the workflow's
  // own default for THIS node (clamped 0-5 server-side); the error route
  // itself is an outgoing edge off the node's red "error" port
  // (sourceHandle === "error"), with onErrorNodeId only as the
  // API-authored fallback - the canvas always draws the edge form.
  retryAttempts?: number;
  onErrorNodeId?: string;
  // wfcontrol (see workflowControl.ts): per-node error strategy - onError (fail | continue | branch), a bounded
  // retry block, retryUnsafe (opt-in to retrying an irreversible node); the Wait node's duration/unit/until/
  // timeout/timeoutUnit/onTimeout (mode is shared with condition); and the hosted-form trigger (triggerType "form"
  // + its form schema, edited by FormTriggerEditor). All honoured server-side only behind their feature flags.
  onError?: string;
  retry?: { maxAttempts?: number; backoffMs?: number; backoff?: string; retryOn?: string };
  retryUnsafe?: boolean;
  duration?: string;
  unit?: string;
  until?: string;
  timeout?: number;
  timeoutUnit?: string;
  onTimeout?: string;
  triggerType?: string;
  form?: unknown;
  deviceId?: string;
  commandType?: string;
  payload?: string;
  toolName?: string;
  argsJson?: string;
  itemsField?: string;
  maxIterations?: number;
  maxItems?: number;
  maxConcurrency?: number;
  operation?: string;
  field?: string;
  pattern?: string;
  replacement?: string;
  template?: string;
  targetWorkflowId?: string;
  // http (HTTP Request)
  method?: string;
  url?: string;
  headers?: HttpHeaderPair[];
  query?: HttpHeaderPair[];
  bodyType?: string;
  // http: request-body STRING. sub_flow: the embedded {nodes, edges}
  // graph OBJECT (see the sub_flow comment above) - same config key,
  // different shape, narrowed at each consumer.
  body?: string | { nodes: any[]; edges: any[] };
  saveAs?: string;
  timeoutSeconds?: number;
  failOnHttpError?: boolean;
  routes?: SupervisorRoute[];
  instructions?: string;
  // voice_menu - IVR-style DTMF menu (see the voice_menu NODE_DEFS entry).
  // `timeoutSeconds` above is shared with http (same key, seconds either way).
  prompt?: string;
  invalidPrompt?: string;
  numDigits?: number;
  finishOnKey?: string;
  maxRetries?: number;
  branches?: VoiceMenuBranch[];
  // bulk_email - see the bulk_email NODE_DEFS entry. `subject` and `body`
  // (shared key with http's request body) are {{var}} templates rendered per
  // recipient; `itemsField` is shared with loop/fan_out.
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
  // crm_lead - built-in Leads CRM node (WorkflowCrmLeadNodeHandler). Every text
  // value supports {{input}} / {{field}} templating. action: upsert | stage | activity.
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
  // Data nodes (set, filter, sort, aggregate, limit, dedupe, switch, merge) - see dataNodes.ts. `mode` (merge/limit),
  // `itemsField` (filter/sort/aggregate/limit/dedupe) and `field` are shared with the keys above.
  assignments?: SetAssignment[];
  keepOnlySet?: boolean;
  conditions?: DataCondition[];
  combinator?: string;
  ignoreCase?: boolean;
  sortKeys?: SortKey[];
  caseSensitive?: boolean;
  aggregations?: AggregateSpec[];
  groupBy?: string;
  count?: number | string;
  dedupeBy?: string;
  keep?: string;
  cases?: SwitchCase[];
  key?: string;
  joinType?: string;
  sources?: string[];
  minInputs?: number | string;
  // Dify-style AI nodes (see aiNodes.ts; mirrors core's WorkflowAiNodeHandler). `source` above is shared with crm_lead.
  // question_classifier: classes + defaultClass + minConfidence + outputMode; parameter_extractor: parameters;
  // variable_aggregator: sources + fallback; document_extractor: source + format + maxChars/maxPages/maxRows + outputMode.
  classes?: AiClassDef[];
  defaultClass?: string;
  minConfidence?: number;
  text?: string;
  instruction?: string;
  provider?: string;
  model?: string;
  outputMode?: string;
  parameters?: AiParamDef[];
  fallback?: string;
  format?: string;
  maxChars?: number;
  maxPages?: number;
  maxRows?: number;
  // data_table - Data Tables node (WorkflowDataTableNodeHandler); `operation` is shared with code/media.
  // filter[].value, valuesJson, table, keyColumn, sortColumn and limit are expression fields.
  table?: string;
  filter?: DataTableFilterRow[];
  valuesJson?: string;
  keyColumn?: string;
  sortColumn?: string;
  sortDirection?: string;
  limit?: string;
  confirmAll?: boolean;
};

// bulk_email recipient sources - mirrors BulkEmailNodeHandler's
// `recipientsSource` (items = array from upstream input, group = a contact
// group's EMAIL contacts, static = a typed list). Limits mirror the server's
// clamps (BulkEmailNodeHandler.MAX_RECIPIENTS_CEILING / MAX_PER_MINUTE_CEILING).
export const BULK_EMAIL_SOURCES = ["items", "group", "static"] as const;
export type BulkEmailSource = (typeof BULK_EMAIL_SOURCES)[number];

// Shape of one NODE_DEFS palette entry - `type`/`category` stay plain strings
// (not literal unions) on purpose: they mirror WorkflowNodeType.java, and a
// backend-added type must be representable here before this file learns
// about it (that's exactly the "future node type ahead of its backend
// support" case the `supported` flag exists for).
export type WorkflowNodeDef = {
  type: string;
  color: string;
  icon: LucideIcon;
  category: string;
  supported: boolean;
  defaultConfig: WorkflowNodeConfig;
};

// The persisted graph shape - the exact {nodes, edges} contract
// WorkflowGraphDto round-trips (see WorkflowCanvas.tsx's toWireGraph/
// fromWireGraph, which map to/from precisely these fields and nothing more).
export type WorkflowGraphNodeData = { label: string; config: WorkflowNodeConfig };
export type WorkflowGraphNode = {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: WorkflowGraphNodeData;
};
export type WorkflowGraphEdge = {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
};

