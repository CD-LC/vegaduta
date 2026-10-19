// Workflow node definitions: the public entry point for the node taxonomy.
// Types live in nodeConfigTypes.ts and the palette catalog in
// nodeDefCatalog.ts; this module re-exports both and keeps the lookups and
// small per-type helpers.
import { NODE_DEFS } from "./nodeDefCatalog";
import type { VoiceMenuBranch, WorkflowGraphEdge, WorkflowGraphNode, WorkflowNodeDef } from "./nodeConfigTypes";

export * from "./nodeConfigTypes";
export { NODE_CATEGORIES, NODE_DEFS } from "./nodeDefCatalog";

// The two implicit Voice Menu ports beyond the configured digits: the
// resolved branch when a wrong digit (no_match) or no input at all (timeout)
// survives every retry. Fixed strings mirrored from
// WorkflowVoiceMenuRequestService - an author cannot rename them.
export const VOICE_MENU_NO_MATCH_HANDLE = "no_match";
export const VOICE_MENU_TIMEOUT_HANDLE = "timeout";

// One output port of a Voice Menu node: `id` is the Handle id, i.e. the
// sourceHandle an edge drawn off it persists, which the backend matches
// (case-insensitively) against the branch WorkflowVoiceMenuRequestService
// resolves. Configured options come first in author order, then the two fixed
// implicit ports. Labels that are blank, duplicated, or collide with a
// reserved handle ("error" is every node's failure port; no_match/timeout are
// the implicit ones) are skipped so React Flow never sees two Handles with the
// same id on one node. Lives here (pure data module) rather than in
// WorkflowNode.tsx so it is unit-testable without React Flow.
export type VoiceMenuPort = { id: string; title: string };

export function voiceMenuPorts(
  config: { branches?: VoiceMenuBranch[] } | undefined,
  t: (key: string) => string,
): VoiceMenuPort[] {
  const reserved = new Set(["error", VOICE_MENU_NO_MATCH_HANDLE, VOICE_MENU_TIMEOUT_HANDLE]);
  const seen = new Set<string>();
  const ports: VoiceMenuPort[] = [];
  for (const branch of config?.branches || []) {
    const id = (branch?.label || "").trim();
    const key = id.toLowerCase();
    if (!id || reserved.has(key) || seen.has(key)) continue;
    seen.add(key);
    ports.push({ id, title: branch.digit ? `${branch.digit} - ${id}` : id });
  }
  ports.push({ id: VOICE_MENU_NO_MATCH_HANDLE, title: t("voiceMenuNoMatchPortTitle") });
  ports.push({ id: VOICE_MENU_TIMEOUT_HANDLE, title: t("voiceMenuTimeoutPortTitle") });
  return ports;
}

// bulk_email limits (sources: BULK_EMAIL_SOURCES in nodeConfigTypes.ts) mirror
// the server's clamps (BulkEmailNodeHandler.MAX_RECIPIENTS_CEILING / MAX_PER_MINUTE_CEILING).
export const BULK_EMAIL_MAX_RECIPIENTS = 500;
export const BULK_EMAIL_MAX_PER_MINUTE = 600;

// Node subtitle/validity helper shared by the canvas node and its test: a
// bulk_email node is only runnable with a subject, a body and a resolvable
// recipient source (the server's OrchestratorService#validate enforces the
// same rules on Validate/Activate/Run).
export function bulkEmailMissing(config: {
  subject?: string;
  body?: unknown;
  recipientsSource?: string;
  groupId?: string;
  staticRecipients?: string;
} | undefined): "subject" | "body" | "group" | "static" | null {
  if (!config?.subject?.trim()) return "subject";
  if (typeof config.body !== "string" || !config.body.trim()) return "body";
  if (config.recipientsSource === "group" && !config.groupId) return "group";
  if (config.recipientsSource === "static" && !config.staticRecipients?.trim()) return "static";
  return null;
}
// Config keys per node type that get {{ }} expression resolution (spec section
// 4.2). MUST match core's Java ExpressionFields exactly - the engine only
// resolves these keys, so a key listed here but not there renders as an
// expression field that silently does nothing. Path grammar: "a.b" nests,
// "list[].value" means the "value" of every element of the "list" array.
// The condition node's "expression" is the one BARE field: a boolean expression
// written without braces (see BARE_EXPRESSION_FIELDS).
export const EXPRESSION_FIELDS: Record<string, readonly string[]> = {
  agent: ["inputMapping.query"],
  condition: ["expression"],
  code: ["template"],
  http: ["url", "body", "headers[].value", "query[].value"],
  tool: ["argsJson"],
  device_control: ["payload"],
  output: ["emailTo", "telegramChatId", "teamsConversationId", "whatsappTo", "driveFolderId", "driveFileName", "fileName", "whatsappMessage", "whatsappTemplateParams[]"],
  bulk_email: ["subject", "body", "replyTo"],
  wait: ["until", "duration"],
  crm_lead: [
    "action", "leadId", "email", "name", "phone", "company", "title", "source", "stage",
    "score", "value", "tags", "owner", "notes", "note", "activityType", "activityBody",
  ],
  // Data nodes (core ExpressionFields): only comparison / assignment values and merge sources are expressions.
  set: ["assignments[].value"],
  filter: ["conditions[].value"],
  switch: ["cases[].conditions[].value"],
  limit: ["count"],
  merge: ["sources[]"],
  question_classifier: ["text"],
  parameter_extractor: ["text"],
  variable_aggregator: ["sources[]"],
  document_extractor: ["source"],
  data_table: ["table", "keyColumn", "valuesJson", "filter[].value", "sortColumn", "limit"],
};

// Fields whose stored text is a whole expression without {{ }}.
export const BARE_EXPRESSION_FIELDS: Record<string, readonly string[]> = {
  condition: ["expression"],
};

export function expressionFieldsOf(type: string | undefined): readonly string[] {
  return (type && EXPRESSION_FIELDS[type]) || [];
}

export function isBareExpressionField(type: string | undefined, field: string): boolean {
  return !!type && (BARE_EXPRESSION_FIELDS[type] || []).includes(field);
}

/** Condition node modes: "contains" is the original text match, "expression" the boolean expression. */
export function conditionMode(config: { mode?: string; expression?: string } | undefined): "contains" | "expression" {
  return config?.mode === "expression" ? "expression" : "contains";
}

export function nodeDef(type: string): WorkflowNodeDef {
  return NODE_DEFS.find((d) => d.type === type) || NODE_DEFS[0];
}

// en.json "nodeDefs" namespace key builders - see the i18n note in nodeDefCatalog.ts.
export function nodeLabelKey(type: string) {
  return `${type}Label`;
}

export function nodeDescriptionKey(type: string) {
  return `${type}Description`;
}

export function categoryLabelKey(category: string) {
  return `category${category}`;
}

export function newNodeId(type: string) {
  return `${type}-${Math.random().toString(36).slice(2, 9)}`;
}

// A brand-new workflow starts with just a Trigger node so "add at least a
// Trigger node" (OrchestratorService#validate) is satisfied immediately.
export function starterGraph(): { nodes: WorkflowGraphNode[]; edges: WorkflowGraphEdge[] } {
  return {
    nodes: [
      {
        id: newNodeId("trigger"),
        type: "trigger",
        position: { x: 80, y: 160 },
        data: { label: "Trigger", config: { channel: null } },
      },
    ],
    edges: [],
  };
}

// Channel-based trigger selection, now per-node (multi-trigger graphs) -
// moved here from WorkflowCanvas.tsx's old workflow-wide toolbar strip so
// both NodeConfigDrawer.tsx's channel picker/hint text and WorkflowNode.tsx's
// read-only per-node badge share one definition instead of two independently
// maintained copies. Plain English on purpose (not routed through
// useTranslations) - this module is data, not a component, same reasoning as
// the i18n note in nodeDefCatalog.ts; it's only ever used to interpolate a channel's display
// name into an already-translated {channel} placeholder, never rendered as a
// standalone label itself. WEBHOOK is included (the old workflow-wide copy
// omitted it, since it was the unnamed default option there) because
// WorkflowNode.tsx's badge must label every possible channel value, default
// included.
export const TRIGGER_CHANNEL_LABELS: Record<string, string> = {
  WEBHOOK: "Webhook",
  WHATSAPP: "WhatsApp",
  CALL: "Phone call",
  SMS: "SMS",
  MESSENGER: "Messenger",
  INSTAGRAM: "Instagram",
  SLACK: "Slack",
  DISCORD: "Discord",
  X: "X",
  EMAIL: "Email",
};
