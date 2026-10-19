// The node palette catalog: one NODE_DEFS entry per workflow node type, plus
// the category order of the palette sections. Re-exported through ./nodeDefs.
import { Zap, Bot, BookOpen, GitBranch, UserCheck, Send, Wrench, Repeat, Code2, Smartphone, GitMerge, Route, Split, Layers, Globe, ClipboardList, Workflow, PhoneCall, Mail, Target, Variable, ListFilter, ArrowUpDown, Sigma, ListEnd, CopyMinus, Signpost, Merge, GitFork, Braces, ListChecks, FileText, Timer, Table2 } from "lucide-react";
import { DATA_CATEGORY } from "./dataNodes";
import { aiNodeDefaultConfig } from "./aiNodes";
import type { WorkflowNodeDef } from "./nodeConfigTypes";

// Interactive Workflow Designer PRD, Section 4 - the node taxonomy, mirrored
// client-side from OrchestratorService/WorkflowNodeType.java. `supported`
// controls whether a palette entry is draggable onto the canvas at all - as
// of Tool/Loop/Code shipping, every node type in this array is real and
// executable, so `supported` (and the disabled-palette-item CSS path it
// drives) is now purely defensive should a future node type ever be added
// ahead of its backend support, same as it protected Tool/Loop/Code while
// they were still "Coming soon" placeholders. `category` groups the palette
// into labeled sections (Start/Intelligence/Logic/Output/Device) instead of
// one flat list - the n8n/Zapier-style pattern research on this feature's
// competitive landscape called out as the easiest thing for a first-time,
// non-technical user to scan.
//
// i18n note (#1002): this is a plain data module, not a component, so it
// cannot call useTranslations() itself. `label`/`description` are therefore
// NOT stored here as literal English - every consumer (WorkflowNode.js,
// NodeConfigDrawer.js, WorkflowCanvas.js) looks the real text up from the
// "nodeDefs" en.json namespace via nodeLabelKey(type)/nodeDescriptionKey(type)
// in nodeDefs.ts, same for `category` via categoryLabelKey(category). `type` and
// `category` values themselves stay plain English identifiers - they're
// never rendered directly, only used to build translation keys and to
// group/compare nodes.
export const NODE_DEFS: WorkflowNodeDef[] = [
  {
    type: "trigger",
    color: "#4f46e5",
    icon: Zap,
    category: "Start",
    supported: true,
    // Multi-trigger graphs (#XXX) - a workflow can now hold more than one
    // Trigger node, each independently bound to its own inbound channel
    // (WhatsApp, Slack, Discord, its own webhook, etc.) instead of the old
    // single workflow-wide trigger-channel setting. `channel` is null until
    // an author picks one in NodeConfigDrawer.tsx's trigger config block
    // (null there falls back to WEBHOOK, same default the old workflow-wide
    // picker used) - see TRIGGER_CHANNEL_LABELS in nodeDefs.ts for the shared label
    // map both that drawer and WorkflowNode.tsx's read-only badge use.
    defaultConfig: { channel: null },
  },
  {
    type: "agent",
    color: "#0284c7",
    icon: Bot,
    category: "Intelligence",
    supported: true,
    defaultConfig: { agentId: "" },
  },
  {
    type: "knowledge",
    color: "#059669",
    icon: BookOpen,
    category: "Intelligence",
    supported: true,
    defaultConfig: { collectionId: "", topK: 5 },
  },
  {
    type: "condition",
    color: "#ca8a04",
    icon: GitBranch,
    category: "Logic",
    supported: true,
    defaultConfig: { contains: "" },
  },
  {
    type: "approval",
    color: "#ea580c",
    icon: UserCheck,
    category: "Logic",
    supported: true,
    // #640: "message" is free text shown to whoever decides this request (see
    // NodeConfigDrawer's Approval section); "adminOnly" is enforced server-side
    // in ApprovalController, not just a UI restriction - see
    // OrchestratorService#authorizeSensitiveNode's javadoc.
    defaultConfig: { message: "", adminOnly: false },
  },
  {
    type: "output",
    color: "#9333ea",
    icon: Send,
    category: "Output",
    supported: true,
    defaultConfig: { channel: "log" },
  },
  {
    type: "device_control",
    color: "#0d9488",
    icon: Smartphone,
    category: "Device",
    supported: true,
    defaultConfig: { deviceId: "", commandType: "TAP", payload: "" },
  },
  {
    type: "tool",
    color: "#0891b2",
    icon: Wrench,
    category: "Intelligence",
    supported: true,
    defaultConfig: { agentId: "", toolName: "", argsJson: "" },
  },
  {
    // "HTTP Request" - the n8n/Zapier connector-catalog staple: one call to
    // any public REST API/webhook, mirrored from WorkflowNodeType.HTTP. url,
    // header values and body accept the Transform node's "{{input}}" /
    // "{{field}}" templating (plus "{{input_json}}"); header values may be
    // vault references ("{{secret.NAME}}") which HttpRequestExecutor resolves
    // and redacts server-side. `saveAs` blank = the response body IS this
    // node's output; set = a JSON object {…input fields, [saveAs]: {status,
    // headers, body}}. Palette label/description come from the "httpNode"
    // en.json namespace via useNodeDefTranslations, not "nodeDefs".
    type: "http",
    color: "#0f766e",
    icon: Globe,
    category: "Intelligence",
    supported: true,
    defaultConfig: {
      method: "GET",
      url: "",
      headers: [],
      query: [],
      bodyType: "none",
      body: "",
      saveAs: "",
      timeoutSeconds: 15,
      failOnHttpError: true,
    },
  },
  {
    type: "loop",
    color: "#7c3aed",
    icon: Repeat,
    category: "Logic",
    supported: true,
    // itemsField blank = this node's own input must already be a JSON
    // array; set = the input is a JSON object and this names the field to
    // read the array from. maxIterations caps a runaway list (25 by
    // default, 100 hard ceiling - see WorkflowExecutionEngine).
    defaultConfig: { itemsField: "", maxIterations: 25 },
  },
  {
    type: "code",
    color: "#db2777",
    icon: Code2,
    category: "Logic",
    supported: true,
    defaultConfig: { operation: "extract", field: "", pattern: "", replacement: "", template: "{{input}}" },
  },
  {
    type: "workflow",
    color: "#16a34a",
    icon: GitMerge,
    category: "Logic",
    supported: true,
    defaultConfig: { targetWorkflowId: "" },
  },
  {
    // Master Roadmap 2026 item #12 (§E) - see WorkflowNodeType.java's javadoc.
    // routes is a list of {handle, label} pairs the author names, one output
    // port per route (wired up in WorkflowCanvas.js/NodeConfigDrawer.js the
    // same way Condition's true/false ports already work, just N-ary instead
    // of binary) - the agent picks one at runtime, never read off the graph's
    // edges directly.
    type: "supervisor",
    color: "#be123c",
    icon: Route,
    category: "Logic",
    supported: true,
    defaultConfig: {
      agentId: "",
      routes: [
        { handle: "route-1", label: "Route 1" },
        { handle: "route-2", label: "Route 2" },
      ],
      instructions: "",
    },
  },
  {
    // Master Roadmap 2026 item #12 (§E) - fans out to every node this one
    // connects to, all executed concurrently against the same input; no
    // config of its own (branches ARE its outgoing edges, unlike Supervisor's
    // config-declared routes) - see WorkflowExecutionEngine's parallel-
    // handling branch.
    type: "parallel",
    color: "#4338ca",
    icon: Split,
    category: "Logic",
    supported: true,
    defaultConfig: {},
  },
  {
    // Dynamic Fan-Out - see WorkflowNodeType.java's javadoc: Loop's item
    // resolution (same itemsField contract, blank = input is already a JSON
    // array, set = the field of the input object holding it) crossed with
    // Parallel's concurrency, over exactly ONE downstream worker node run
    // once per runtime item CONCURRENTLY, per-item outputs merged into a
    // JSON array in item order. maxItems caps the list (25 default, 100 hard
    // ceiling) and maxConcurrency the simultaneous workers (5 default, 10
    // hard ceiling) - both clamped server-side in WorkflowExecutionEngine's
    // fanOutMaxItems/fanOutConcurrency, this just mirrors the defaults.
    type: "fan_out",
    color: "#c026d3",
    icon: Layers,
    category: "Logic",
    supported: true,
    defaultConfig: { itemsField: "", maxItems: 25, maxConcurrency: 5 },
  },
  {
    // "Human Input" (Tier-3) - pauses the run to collect STRUCTURED data
    // mid-run (the gap between Approval's approve/reject-only contract and
    // a full re-trigger): snapshots `fields` into a PENDING
    // workflow_input_request, halts the run AWAITING_INPUT, and resumes
    // with {"form": {...answers}, "input": <upstream>} as output once
    // POST /api/workflows/input-requests/{id}/submit lands. `fields` uses
    // the HumanInputField shape (nodeConfigTypes.ts); an empty fields list is legal -
    // the node then acts as a pure acknowledge/pause step.
    type: "human_input",
    color: "#b45309",
    icon: ClipboardList,
    category: "Logic",
    supported: true,
    defaultConfig: { message: "", fields: [] },
  },
  {
    // "Sub-Flow" (Tier-3) - true sub-graph iteration: where Loop repeats
    // ONE downstream node per item, sub_flow carries a whole embedded
    // body graph ({nodes, edges}, same wire shape as the outer graph) in
    // `body` and walks it once per item resolved via the same itemsField
    // contract Loop uses. `entryNodeId` overrides which body node runs
    // first (blank = the body's single no-incoming-edge node). Per-item
    // terminal outputs merge into a JSON array - same result shape as
    // Loop/Fan-Out. The canvas edits `body` as raw JSON in the drawer; a
    // nested visual editor is deliberately out of scope (see
    // WorkflowNodeType.SUB_FLOW's javadoc for the engine contract).
    type: "sub_flow",
    color: "#4d7c0f",
    icon: Workflow,
    category: "Logic",
    supported: true,
    defaultConfig: { itemsField: "", maxIterations: 25, entryNodeId: "", body: { nodes: [], edges: [] } },
  },
  {
    // "Voice Menu" - a dedicated IVR menu for CALL-triggered workflows,
    // mirrored from WorkflowNodeType.VOICE_MENU (see its javadoc for the
    // halt/resume mechanics). Speaks `prompt`, collects DTMF digits
    // (`numDigits`, or until `finishOnKey` when numDigits is blank) waiting up
    // to `timeoutSeconds`, re-prompts up to `maxRetries` times on no input or
    // an unmatched digit (using `invalidPrompt` if set), then routes down the
    // output port named by the matched `branches` entry's `label` - or the
    // implicit no_match/timeout ports (VOICE_MENU_*_HANDLE in nodeDefs.ts) once retries
    // are spent. Server clamps timeoutSeconds to 1-60 and maxRetries to 0-5.
    // Call LOGIC only: nothing here selects an STT/TTS model or codec.
    type: "voice_menu",
    color: "#0e7490",
    icon: PhoneCall,
    category: "Logic",
    supported: true,
    defaultConfig: {
      prompt: "",
      invalidPrompt: "",
      numDigits: 1,
      finishOnKey: "#",
      timeoutSeconds: 5,
      maxRetries: 2,
      branches: [
        { digit: "1", label: "option-1" },
        { digit: "2", label: "option-2" },
      ],
    },
  },
  {
    // "Bulk Email" - mirrored from WorkflowNodeType.BULK_EMAIL. One node
    // sends a personalized email to MANY recipients: `recipientsSource`
    // picks an array from the node's input (`itemsField`, objects carry
    // {{vars}} + `emailField`), a contact group's EMAIL contacts, or a typed
    // list. Server-side it is throttled (`maxPerMinute`, bounded per run),
    // checks the tenant suppression list, appends an unsubscribe link +
    // List-Unsubscribe headers, optionally skips recipients who already
    // replied (`skipIfReplied`), and returns per-recipient rows as its
    // output. Replies/bounces arrive as email.reply_received / email.bounced
    // events.
    type: "bulk_email",
    color: "#be185d",
    icon: Mail,
    category: "Output",
    supported: true,
    defaultConfig: {
      recipientsSource: "items",
      itemsField: "",
      emailField: "email",
      groupId: "",
      staticRecipients: "",
      subject: "",
      body: "",
      html: false,
      replyTo: "",
      maxPerMinute: 60,
      maxRecipients: 100,
      skipIfReplied: false,
      includeUnsubscribe: true,
    },
  },
  {
    // "CRM Lead" - mirrored from WorkflowNodeType.CRM_LEAD (see
    // WorkflowCrmLeadNodeHandler): upsert a lead by email, move its stage, or
    // add a timeline activity in the built-in Leads CRM. Text fields take the
    // same {{input}} / {{field}} templating as the HTTP node. Config form
    // strings live in the "crmLeadNode" en.json namespace.
    type: "crm_lead",
    color: "#b45309",
    icon: Target,
    category: "Output",
    supported: true,
    defaultConfig: { action: "upsert", email: "{{email}}", name: "{{name}}", source: "workflow", stage: "", activityType: "NOTE" },
  },
  // ---- Data nodes (n8n node-pack parity) - pure, deterministic, no LLM. Mirrored from
  // WorkflowNodeType SET..MERGE / core's WorkflowDataOps; config shapes live in dataNodes.ts and the forms in
  // DataNodeConfigs.tsx. Palette strings come from the "workflowNodesData" namespace (useNodeDefTranslations).
  {
    // Assign named fields (values may be expressions); optionally keep only those.
    type: "set",
    color: "#2563eb",
    icon: Variable,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { assignments: [{ name: "", value: "", type: "auto" }], keepOnlySet: false },
  },
  {
    // Keep the items of a JSON array that satisfy field / operator / value conditions.
    type: "filter",
    color: "#0ea5a4",
    icon: ListFilter,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { itemsField: "", conditions: [{ field: "", operator: "eq", value: "" }], combinator: "and", ignoreCase: false },
  },
  {
    // Stable, number/date/text-aware ordering of a JSON array by one or more fields.
    type: "sort",
    color: "#6366f1",
    icon: ArrowUpDown,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { itemsField: "", sortKeys: [{ field: "", direction: "asc", type: "auto" }], caseSensitive: false },
  },
  {
    // count / sum / avg / min / max / first / last / concat / collect, optionally grouped.
    type: "aggregate",
    color: "#d97706",
    icon: Sigma,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { itemsField: "", groupBy: "", aggregations: [{ operation: "count", field: "", as: "" }] },
  },
  {
    // Keep the first or last N items.
    type: "limit",
    color: "#64748b",
    icon: ListEnd,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { itemsField: "", count: 10, mode: "first" },
  },
  {
    // Remove duplicates by a field set (blank = the whole item), keeping the first or last of each.
    type: "dedupe",
    color: "#a21caf",
    icon: CopyMinus,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { itemsField: "", dedupeBy: "", keep: "first", ignoreCase: false },
  },
  {
    // N named cases plus a fixed "default" port. Each case name is an output port (sourceHandle) - the same
    // branch mechanism as condition true/false and supervisor routes; the FIRST matching case wins.
    type: "switch",
    color: "#e11d48",
    icon: Signpost,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: {
      cases: [{ name: "case-1", combinator: "and", conditions: [{ field: "", operator: "eq", value: "" }] }],
      ignoreCase: false,
    },
  },
  {
    // Combine inputs: append, join by key, or first non-empty. Inputs are the node's own input array (the join
    // after a Parallel / Fan-out / Loop) or the `sources` expressions - see WorkflowDataOps#merge for why it cannot
    // suspend until N independent branches arrive.
    type: "merge",
    color: "#15803d",
    icon: Merge,
    category: DATA_CATEGORY,
    supported: true,
    defaultConfig: { mode: "append", sources: [], key: "", joinType: "left", minInputs: 0 },
  },
  {
    // Dify-style AI nodes (core: WorkflowAiNodeHandler; helpers + validation in aiNodes.ts). Palette label/description
    // strings live in the "workflowNodesAi" namespace (see useNodeDefTranslations), not "nodeDefs".
    // "Question Classifier" routes like Supervisor/Condition: one output port per class, the class `handle` is the
    // edge's sourceHandle; a model call at temperature 0 picks exactly one class, anything unusable routes to the
    // default class.
    type: "question_classifier",
    color: "#7e22ce",
    icon: GitFork,
    category: "Logic",
    supported: true,
    defaultConfig: aiNodeDefaultConfig("question_classifier"),
  },
  {
    // "Parameter Extractor" - typed JSON extraction; absent required values are listed under `_missing`.
    type: "parameter_extractor",
    color: "#2563eb",
    icon: Braces,
    category: "Intelligence",
    supported: true,
    defaultConfig: aiNodeDefaultConfig("parameter_extractor"),
  },
  {
    // "Variable Aggregator" - no model: the first non-empty of N source values.
    type: "variable_aggregator",
    color: "#a16207",
    icon: ListChecks,
    category: "Logic",
    supported: true,
    defaultConfig: aiNodeDefaultConfig("variable_aggregator"),
  },
  {
    // "Document Extractor" - an uploaded file / page / text to plain text, with size, page and row caps.
    type: "document_extractor",
    color: "#475569",
    icon: FileText,
    category: "Intelligence",
    supported: true,
    defaultConfig: aiNodeDefaultConfig("document_extractor"),
  },
  {
    // "Wait" - mirrored from WorkflowNodeType.WAIT (see WorkflowWaitService): parks the run durably (stored, not an
    // in-memory timer) for a `duration` (+ `unit`), until an `until` timestamp, or until a signed resume URL is
    // called (`mode: "webhook"`, with a `timeout` and `onTimeout`). Gated server-side by
    // app.workflow.wait-node.enabled - with the flag off a graph containing one fails validation. Palette
    // label/description come from the "workflowForms" en.json namespace via useNodeDefTranslations.
    type: "wait",
    color: "#0369a1",
    icon: Timer,
    category: "Logic",
    supported: true,
    defaultConfig: { mode: "duration", duration: "1", unit: "hours" },
  },
  {
    // "Data Table" - mirrored from WorkflowNodeType.DATA_TABLE (see WorkflowDataTableNodeHandler): read, insert,
    // update, upsert, delete or count rows of a tenant data table so a workflow keeps state between runs. The server
    // flag app.data-tables.enabled defaults OFF; while off the node is skipped. Form strings live in the
    // "dataTables" en.json namespace (node* keys), canvas subtitles in "workflowNode" (dataTable_*).
    type: "data_table",
    color: "#0f766e",
    icon: Table2,
    category: "Logic",
    supported: true,
    defaultConfig: { table: "", operation: "get_rows", filter: [] },
  },
];

// Stable render order for palette section headers - Object grouping alone
// doesn't preserve a deliberate category order, and "Start" should always lead.
export const NODE_CATEGORIES = ["Start", "Intelligence", "Logic", DATA_CATEGORY, "Output", "Device"];
