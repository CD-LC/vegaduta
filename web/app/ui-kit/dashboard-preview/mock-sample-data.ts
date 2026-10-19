// Sample JSON for the dev-only preview mock (mock-api.ts): dashboard data first,
// then the console-preview surfaces. Everything here is clearly sample data.

export type JsonBody = unknown;

const now = Date.now();
export const iso = (msAgo: number) => new Date(now - msAgo).toISOString();
export const MIN = 60_000;
export const HOUR = 3_600_000;
export const DAY = 86_400_000;

// ---- Sample data --------------------------------------------------------

// GET /api/agents - AgentResponse[] (DashboardHome reads id/name/providerName/model).
export const agents = [
  {
    id: "agt_support_copilot",
    name: "Support copilot",
    description: "Answers order and delivery questions over WhatsApp.",
    providerName: "PLATFORM",
    model: "gemini-2.5-flash",
    status: "ACTIVE",
    systemPrompt: "You are the support copilot for Acme Traders.",
    createdAt: iso(21 * DAY),
    updatedAt: iso(2 * HOUR),
  },
  {
    id: "agt_invoice_chaser",
    name: "Invoice chaser",
    description: "Nudges overdue invoices by email and Telegram.",
    providerName: "OPENAI",
    model: "gpt-4.1-mini",
    status: "ACTIVE",
    systemPrompt: "You chase overdue invoices politely.",
    createdAt: iso(14 * DAY),
    updatedAt: iso(5 * HOUR),
  },
  {
    id: "agt_lead_qualifier",
    name: "Lead qualifier",
    description: "Scores inbound leads and books discovery calls.",
    providerName: "ANTHROPIC",
    model: "claude-sonnet-4",
    status: "ACTIVE",
    systemPrompt: "You qualify inbound leads.",
    createdAt: iso(9 * DAY),
    updatedAt: iso(1 * DAY),
  },
  {
    id: "agt_stock_watcher",
    name: "Stock watcher",
    description: "Reorders low-stock SKUs after approval.",
    providerName: "PLATFORM",
    model: "gemini-2.5-flash",
    status: "PAUSED",
    systemPrompt: "You watch stock levels.",
    createdAt: iso(4 * DAY),
    updatedAt: iso(3 * DAY),
  },
];

// GET /api/workflows - WorkflowResponse[] (DashboardHome reads id/name/status).
export const workflows = [
  {
    id: "wf_morning_digest",
    name: "Morning sales digest",
    description: "Summarises yesterday's orders and posts to the owner.",
    status: "ACTIVE",
    createdAt: iso(20 * DAY),
    updatedAt: iso(6 * HOUR),
    lastRunAt: iso(6 * HOUR),
  },
  {
    id: "wf_refund_review",
    name: "Refund review",
    description: "Routes refund requests above Rs 5,000 for approval.",
    status: "RUNNING",
    createdAt: iso(12 * DAY),
    updatedAt: iso(12 * MIN),
    lastRunAt: iso(12 * MIN),
  },
  {
    id: "wf_supplier_sync",
    name: "Supplier price sync",
    description: "Pulls supplier price lists every night.",
    status: "FAILED",
    createdAt: iso(7 * DAY),
    updatedAt: iso(9 * HOUR),
    lastRunAt: iso(9 * HOUR),
  },
];

// GET /api/system/status/llm-telemetry - LlmDashboardMetrics.
export const llmTelemetry = {
  tokensPerMinuteRecent: 1840,
  costTodayUsd: 1.37,
  costPerConversationTodayUsd: 0.0182,
  callsLast24h: 412,
  conversationsToday: 75,
  p50LatencyMs: 640,
  p95LatencyMs: 1820,
  p99LatencyMs: 3460,
  modelSplitLast24h: [
    { model: "gemini-2.5-flash", percent: 58 },
    { model: "gpt-4.1-mini", percent: 27 },
    { model: "claude-sonnet-4", percent: 15 },
  ],
  // Extra series (not read by the dashboard today, handy for future charts).
  daily: [6, 5, 4, 3, 2, 1, 0].map((d) => ({
    date: new Date(now - d * DAY).toISOString().slice(0, 10),
    tokens: [38_200, 41_900, 35_600, 47_300, 52_100, 44_800, 29_400][d],
    costUsd: [1.12, 1.24, 1.05, 1.41, 1.58, 1.33, 0.87][d],
    calls: [380, 402, 351, 455, 498, 431, 287][d],
  })),
};

// GET /api/system/status - SystemStatusResponse (TrustStrip reads
// database/redis/kafka/sidecar.status, AppShell reads overallStatus,
// DashboardHome reads kafka.* and jvm.uptimeSeconds).
export const systemStatus = {
  overallStatus: "OK",
  database: { status: "UP", detail: "PostgreSQL 16, pool 3/20" },
  redis: { status: "UP" },
  kafka: {
    status: "UP",
    config: { topic: "agent-events", partitions: 6, consumerGroup: "core-workers" },
    queue: { pendingCount: 3, deadLetteredCount: 0 },
    dlq: { messageCount: 0 },
  },
  sidecar: { status: "UP", version: "0.9.4" },
  mcp: { registered: 3, healthy: 2, enabledServers: 2, totalServers: 3 },
  jvm: {
    uptimeSeconds: 3 * 86_400 + 5 * 3_600 + 12 * 60,
    heapUsedMb: 612,
    heapMaxMb: 2048,
  },
  timestamp: iso(0),
};

// GET /api/channels/status - ChannelStatusController aggregate.
export const channelsStatus = {
  channels: [
    { key: "whatsapp", label: "WhatsApp", state: "CONNECTED", detail: "+91 98xxx xx210" },
    { key: "telegram", label: "Telegram", state: "CONNECTED", detail: "@acme_traders_bot" },
    { key: "email", label: "Email", state: "NOT_CONFIGURED", detail: null },
  ],
  checkedAt: iso(0),
};

// GET /api/approvals?status=PENDING - ApprovalResponse[] (DashboardHome reads actionType).
export const pendingApprovals = [
  {
    id: "apr_1",
    status: "PENDING",
    actionType: "channel.whatsapp.send_template",
    agentId: "agt_support_copilot",
    agentName: "Support copilot",
    summary: "Send delivery-delay template to 42 customers",
    requestedAt: iso(35 * MIN),
    requestedBy: "Support copilot",
  },
  {
    id: "apr_2",
    status: "PENDING",
    actionType: "payments.refund.issue",
    agentId: "agt_invoice_chaser",
    agentName: "Invoice chaser",
    summary: "Refund Rs 7,250 to order #A-10432",
    requestedAt: iso(2 * HOUR),
    requestedBy: "Refund review",
  },
];

// GET /api/tenant/settings - TenantSettingsResponse.
export const tenantSettings = {
  tenantId: "tnt_preview",
  organizationName: "Acme Traders (preview)",
  orgName: "Acme Traders (preview)",
  name: "Acme Traders (preview)",
  plan: "FREE",
  experienceMode: "ADVANCED",
  usesPlatformPool: true,
  defaultProviderName: "PLATFORM",
  defaultModel: "gemini-2.5-flash",
  timezone: "Asia/Kolkata",
  locale: "en",
};

// GET /api/tenant/usage-quota - TenantUsageQuotaResponse.
export const usageQuota = {
  plan: "FREE",
  usesPlatformPool: true,
  tokensUsedMonth: 1_620_000,
  tokensMonthlyLimit: 5_000_000,
};

// GET /api/audit-log - AuditLogEntryResponse[] (AdminEventTail).
export const auditLog = [
  ["AGENT_CHAT_TURN", "AGENT", "agt_support_copilot", "WhatsApp · order status lookup", 3 * MIN],
  ["WORKFLOW_RUN_STARTED", "WORKFLOW", "wf_refund_review", "trigger: inbound refund request", 12 * MIN],
  ["APPROVAL_REQUESTED", "APPROVAL", "apr_1", "channel.whatsapp.send_template", 35 * MIN],
  ["TOOL_CALL", "TOOL", "orders.lookup", "order #A-10432 → SHIPPED", 41 * MIN],
  ["AGENT_CHAT_TURN", "AGENT", "agt_lead_qualifier", "web chat · lead scored 82", 58 * MIN],
  ["CHANNEL_MESSAGE_SENT", "CHANNEL", "telegram", "invoice reminder to 3 accounts", 1 * HOUR + 10 * MIN],
  ["WORKFLOW_RUN_FAILED", "WORKFLOW", "wf_supplier_sync", "supplier portal returned 503", 9 * HOUR],
  ["AGENT_UPDATED", "AGENT", "agt_stock_watcher", "paused by owner", 3 * DAY],
].map(([actionType, targetType, targetId, detail, ago], i) => ({
  id: `aud_${i + 1}`,
  actionType,
  targetType,
  targetId,
  outcome: actionType === "WORKFLOW_RUN_FAILED" ? "FAILURE" : "SUCCESS",
  detail,
  actor: "system",
  createdAt: iso(ago as number),
}));

// GET /api/notifications - NotificationResponse[].
export const notifications = [
  {
    id: "ntf_1",
    category: "WORKFLOW",
    title: "Supplier price sync failed",
    body: "The supplier portal returned 503 three times. The run will retry tonight.",
    severity: "WARNING",
    read: false,
    createdAt: iso(9 * HOUR),
    link: "/workflows",
    relatedIssueId: null as string | null,
  },
  {
    id: "ntf_2",
    category: "SYSTEM",
    title: "Two approvals waiting",
    body: "Support copilot and Invoice chaser are waiting on you.",
    severity: "INFO",
    read: true,
    createdAt: iso(2 * HOUR),
    link: "/approvals",
    relatedIssueId: null,
  },
];

// GET /api/account - AccountResponse (AppChromeProvider; only when a username is present).
export const account = {
  username: "priya.preview",
  email: "priya@acme-traders.example",
  displayName: "Priya Sharma",
  firstName: "Priya",
  lastName: "Sharma",
  avatarUrl: null as string | null,
  locale: "en",
};


// ---- Console-preview sample data (/ui-kit/console-preview/*) -------------
// Everything below is clearly sample: "(sample)" suffixes, example.com
// hosts, round numbers. Shapes follow app/lib/api/*.ts + the panels that
// read them (ProvidersPanel, Knowledge*Panel, McpPanel, UserToolScriptsPanel,
// OpenApiToolSourcesPanel, admin/page.tsx, admin/billing/page.tsx, usage).

// GET /api/providers - BYOK credentials (ProvidersPanel reads providerName/status).
export const providers = [
  { id: "prv_1", providerName: "OPENAI", status: "ACTIVE", keyPreview: "...k7Qz", createdAt: iso(30 * DAY), updatedAt: iso(3 * DAY) },
  { id: "prv_2", providerName: "ANTHROPIC", status: "ACTIVE", keyPreview: "...9vLm", createdAt: iso(18 * DAY), updatedAt: iso(18 * DAY) },
  { id: "prv_3", providerName: "GOOGLE", status: "INACTIVE", keyPreview: "...a2Xe", createdAt: iso(40 * DAY), updatedAt: iso(12 * DAY) },
];

// GET /api/providers/{name}/models - ProviderModelInfo[].
export const providerModels: Record<string, { modelId: string; displayName: string; contextWindow?: number }[]> = {
  OPENAI: [
    { modelId: "gpt-4.1-mini", displayName: "GPT-4.1 mini", contextWindow: 1_000_000 },
    { modelId: "gpt-4.1", displayName: "GPT-4.1", contextWindow: 1_000_000 },
  ],
  ANTHROPIC: [
    { modelId: "claude-sonnet-4", displayName: "Claude Sonnet 4", contextWindow: 200_000 },
    { modelId: "claude-haiku-4", displayName: "Claude Haiku 4", contextWindow: 200_000 },
  ],
  PLATFORM: [{ modelId: "gemini-2.5-flash", displayName: "Gemini 2.5 Flash", contextWindow: 1_000_000 }],
  GOOGLE: [{ modelId: "gemini-2.5-flash", displayName: "Gemini 2.5 Flash", contextWindow: 1_000_000 }],
};

// GET /api/knowledge/collections - KnowledgeCollectionResponse[].
export const collections = [
  { id: "col_default", name: "Default", description: "Everything uploaded without a folder.", isDefault: true, parentCollectionId: null as string | null, embeddingModel: "text-embedding-3-small", fileCount: 6, createdAt: iso(40 * DAY), updatedAt: iso(2 * DAY) },
  { id: "col_policies", name: "Returns & refunds policy (sample)", description: "Store policies the support copilot quotes from.", isDefault: false, parentCollectionId: null as string | null, embeddingModel: "text-embedding-3-small", fileCount: 3, createdAt: iso(21 * DAY), updatedAt: iso(5 * DAY) },
  { id: "col_catalog", name: "Product catalogue (sample)", description: "SKU sheets and spec PDFs.", isDefault: false, parentCollectionId: null as string | null, embeddingModel: "text-embedding-3-small", fileCount: 12, createdAt: iso(14 * DAY), updatedAt: iso(9 * HOUR) },
];

// GET /api/knowledge/collections/{id}/documents - KnowledgeDocumentResponse[].
type DocRow = [string, string, string, string, number, number, number];
const docRows: Record<string, DocRow[]> = {
  col_default: [
    ["doc_1", "onboarding-notes.md", "md", "INDEXED", 14, 18_400, 3 * DAY],
    ["doc_2", "faq-2026.pdf", "pdf", "INDEXED", 42, 412_000, 6 * DAY],
    ["doc_3", "warehouse-map.png", "png", "INDEXED", 2, 1_280_000, 8 * DAY],
    ["doc_4", "supplier-call-2026-09.mp3", "mp3", "PROCESSING", 0, 9_400_000, 40 * MIN],
    ["doc_5", "old-price-list.xlsx", "xlsx", "FAILED", 0, 88_000, 2 * DAY],
    ["doc_6", "delivery-sla.docx", "docx", "INDEXED", 9, 64_000, 12 * DAY],
  ],
  col_policies: [
    ["doc_7", "returns-policy-v3.pdf", "pdf", "INDEXED", 21, 240_000, 5 * DAY],
    ["doc_8", "refund-matrix.md", "md", "INDEXED", 6, 9_200, 5 * DAY],
    ["doc_9", "gst-invoice-rules.pdf", "pdf", "INDEXED", 17, 310_000, 20 * DAY],
  ],
  col_catalog: Array.from({ length: 12 }, (_, i): DocRow => [
    `doc_c${i + 1}`,
    `sku-sheet-${String(i + 1).padStart(2, "0")}.pdf`,
    "pdf",
    i === 10 ? "PROCESSING" : "INDEXED",
    i === 10 ? 0 : 8 + i,
    120_000 + i * 9_000,
    (i + 1) * 6 * HOUR,
  ]),
};
export const documentsByCollection: Record<string, unknown[]> = Object.fromEntries(
  Object.entries(docRows).map(([key, rows]) => [
    key,
    rows.map(([id, filename, ext, status, chunkCount, fileSizeBytes, ago]) => ({
      id,
      filename,
      fileExtension: ext,
      status,
      chunkCount,
      fileSizeBytes,
      hasOriginalFile: true,
      errorMessage: status === "FAILED" ? "Unsupported spreadsheet layout - export as CSV and retry." : null,
      collectionId: key,
      createdAt: iso(ago),
      updatedAt: iso(ago),
    })),
  ])
);

// GET /api/knowledge/stats - KnowledgeStatsResponse.
export const knowledgeStats = { totalCollections: 3, totalDocuments: 21, totalVectors: 2_482, indexedDocuments: 19, processingDocuments: 2, failedDocuments: 1 };

// GET /api/knowledge/collections/embedding-models.
export const embeddingModels = [
  { key: "text-embedding-3-small", label: "OpenAI text-embedding-3-small", isDefault: true },
  { key: "gemini-embedding-001", label: "Gemini embedding 001", isDefault: false },
];

// GET /api/mcp/registry - this tenant's MCP registrations (McpPanel).
export const mcpServers = [
  { id: "mcp_github", name: "GitHub (sample)", type: "STDIO", command: "npx", args: ["-y", "@modelcontextprotocol/server-github"], url: null as string | null, description: "Issues, pull requests and file reads for the acme-traders org.", enabled: true, dynamic: true, connectionStatus: "CONNECTED", lastError: null as string | null, toolCount: 18, createdAt: iso(12 * DAY), updatedAt: iso(1 * HOUR) },
  { id: "mcp_slack", name: "Slack (sample)", type: "STDIO", command: "npx", args: ["-y", "@modelcontextprotocol/server-slack"], url: null as string | null, description: "Post to #ops and read channel history.", enabled: true, dynamic: true, connectionStatus: "CONNECTED", lastError: null as string | null, toolCount: 9, createdAt: iso(9 * DAY), updatedAt: iso(3 * HOUR) },
  { id: "mcp_orders", name: "Orders API (remote, sample)", type: "REMOTE", command: null as string | null, args: [], url: "https://mcp.example.com/orders", description: "Order status and shipment lookups for the storefront.", enabled: false, dynamic: true, connectionStatus: "FAILED", lastError: "401 from https://mcp.example.com/orders - check the API key.", toolCount: 0, createdAt: iso(4 * DAY), updatedAt: iso(2 * DAY) },
];

// GET /api/mcp/registry/catalog - curated gallery (McpCatalogService).
export const mcpCatalog = [
  { key: "github", name: "GitHub", type: "STDIO", category: "Developer", description: "Repositories, issues and pull requests.", command: "npx", args: ["-y", "@modelcontextprotocol/server-github"], docsUrl: "https://example.com/docs/github" },
  { key: "slack", name: "Slack", type: "STDIO", category: "Communication", description: "Channels, messages and search.", command: "npx", args: ["-y", "@modelcontextprotocol/server-slack"], docsUrl: "https://example.com/docs/slack" },
  { key: "postgres", name: "PostgreSQL", type: "STDIO", category: "Data", description: "Read-only SQL over a connection string.", command: "npx", args: ["-y", "@modelcontextprotocol/server-postgres"], docsUrl: "https://example.com/docs/postgres" },
  { key: "filesystem", name: "Filesystem", type: "STDIO", category: "Utilities", description: "Read and write files under an allowed root.", command: "npx", args: ["-y", "@modelcontextprotocol/server-filesystem"], docsUrl: "https://example.com/docs/fs" },
  { key: "brave", name: "Brave Search", type: "STDIO", category: "Web", description: "Web and local search results.", command: "npx", args: ["-y", "@modelcontextprotocol/server-brave-search"], docsUrl: "https://example.com/docs/brave" },
  { key: "atlassian", name: "Atlassian", type: "REMOTE", category: "Productivity", description: "Jira issues and Confluence pages.", url: "https://mcp.atlassian.example.com/v1/sse", docsUrl: "https://example.com/docs/atlassian" },
];

// GET /api/mcp/registry/search?q= - public registry proxy (RegistryBrowser).
export const mcpRegistrySearch = {
  results: [
    { id: "io.example/weather", name: "Weather (sample)", description: "Current conditions and forecasts by city.", version: "1.2.0", command: "npx", args: ["-y", "@example/mcp-weather"], env: {}, url: null as string | null, homepage: "https://example.com/weather" },
    { id: "io.example/sheets", name: "Google Sheets (sample)", description: "Read and append rows.", version: "0.8.3", command: "npx", args: ["-y", "@example/mcp-sheets"], env: { GOOGLE_TOKEN: "" }, url: null as string | null, homepage: "https://example.com/sheets" },
    { id: "io.example/crm", name: "CRM remote (sample)", description: "Contacts and deals over SSE.", version: "2.0.1", command: null as string | null, args: [], env: {}, url: "https://crm.example.com/mcp", homepage: "https://example.com/crm" },
  ],
  nextCursor: null as string | null,
};

// GET /api/tools/scripts - UserToolScript[].
export const toolScripts = [
  { id: "scr_1", toolName: "gst_amount", displayName: "GST amount (sample)", description: "18% GST on a base amount.", expression: "#input.amount * 0.18", inputSchemaJson: '{"type":"object","properties":{"amount":{"type":"number"}}}', enabled: true, createdAt: iso(10 * DAY), updatedAt: iso(2 * DAY) },
  { id: "scr_2", toolName: "order_ref", displayName: "Order reference (sample)", description: "Builds A-<id> order references.", expression: "'A-' + #input.id", inputSchemaJson: '{"type":"object","properties":{"id":{"type":"string"}}}', enabled: false, createdAt: iso(6 * DAY), updatedAt: iso(6 * DAY) },
];

// GET /api/tools/openapi - OpenApiToolSource[]; /{id}/tools - OpenApiTool[].
export const openApiSources = [
  { id: "oas_store", name: "Storefront API (sample)", toolPrefix: "store", baseUrl: "https://api.example.com/v1", specTitle: "Storefront", specVersion: "1.4.0", authType: "API_KEY_HEADER", authHeaderName: "X-API-Key", hasSecret: true, secretPreview: "...4242", enabled: true, toolCount: 7, enabledToolCount: 5, lastRefreshedAt: iso(1 * DAY), lastError: null as string | null, createdAt: iso(15 * DAY), updatedAt: iso(1 * DAY) },
];
type ToolRow = [string, string, string, string, string, boolean];
const openApiToolRows: ToolRow[] = [
  ["oat_1", "store_get_order", "GET", "/orders/{orderId}", "Fetch one order", true],
  ["oat_2", "store_list_orders", "GET", "/orders", "List orders", true],
  ["oat_3", "store_create_refund", "POST", "/refunds", "Issue a refund", false],
  ["oat_4", "store_get_stock", "GET", "/stock/{sku}", "Stock for a SKU", true],
  ["oat_5", "store_update_address", "PUT", "/orders/{orderId}/address", "Change the delivery address", true],
  ["oat_6", "store_cancel_order", "DELETE", "/orders/{orderId}", "Cancel an order", false],
  ["oat_7", "store_track", "GET", "/shipments/{id}/track", "Track a shipment", true],
];
export const openApiTools = openApiToolRows.map(([id, toolName, httpMethod, pathTemplate, summary, enabled]) => ({
  id,
  toolName,
  httpMethod,
  pathTemplate,
  summary,
  description: summary,
  enabled,
  deprecated: false,
  sourceId: "oas_store",
  inputSchemaJson: '{"type":"object","properties":{}}',
}));

// GET /api/tools/classifications - ToolClassificationListResponse.
export const toolClassifications = {
  enforced: true,
  tools: [
    { toolName: "orders_lookup", displayName: "Orders lookup", origin: "BUILT_IN", trust: "TRUSTED_INTERNAL", sensitivity: "READ_TENANT", determinism: "PURE" },
    { toolName: "whatsapp_send_template", displayName: "WhatsApp template", origin: "BUILT_IN", trust: "TRUSTED_INTERNAL", sensitivity: "EGRESS", determinism: "SIDE_EFFECT" },
    { toolName: "github_create_issue", displayName: "Create issue", origin: "MCP", serverId: "mcp_github", serverName: "GitHub (sample)", trust: "UNTRUSTED_EXTERNAL", sensitivity: "WRITE_TENANT", determinism: "DURABLE" },
    { toolName: "gst_amount", displayName: "GST amount (sample)", origin: "USER_SCRIPT", trust: "TENANT_SCOPED", sensitivity: "READ_PUBLIC", determinism: "PURE" },
  ],
};

// GET /api/admin/users - UserAdminController.
export const adminUsers = [
  { id: "usr_1", username: "priya.admin.preview", email: "priya@acme-traders.example", firstName: "Priya", lastName: "Sharma", roles: ["tenant-admin"], permissions: [] as string[], enabled: true, createdAt: iso(60 * DAY) },
  { id: "usr_2", username: "arjun.preview", email: "arjun@acme-traders.example", firstName: "Arjun", lastName: "Mehta", roles: ["tenant-user"], permissions: [] as string[], enabled: true, createdAt: iso(30 * DAY) },
  { id: "usr_3", username: "neha.preview", email: "neha@acme-traders.example", firstName: "Neha", lastName: "Rao", roles: ["tenant-user"], permissions: [] as string[], enabled: false, createdAt: iso(12 * DAY) },
];

// GET /api/tenant/subscription, /referral, /invoices - billing surfaces.
export const subscription = { plan: "FREE", subscriptionStatus: "ACTIVE", cancelScheduled: false, pastDue: false, compPlanReason: null as string | null, compPlanExpiresAt: null as string | null };
export const referral = { code: "PRIYA-7F3K", redemptionsCount: 2, redeemedByMe: false, canRedeem: true };
export const invoices = [
  { id: "inv_2026_08", invoiceNumber: "VD-2026-000418", invoiceDate: iso(24 * DAY), amount: 0, currency: "INR", status: "PAID" },
  { id: "inv_2026_07", invoiceNumber: "VD-2026-000377", invoiceDate: iso(55 * DAY), amount: 0, currency: "INR", status: "PAID" },
];

// GET /api/tenant/spend-protection - SpendProtectionResponse (usage page).
export const spendProtection = {
  generatedAt: iso(0),
  cacheWindowDays: 7,
  caching: [
    { provider: "ANTHROPIC", active: true, callsInWindow: 212, cacheReadTokenShare: 0.41, callsWithCacheHitShare: 0.63 },
    { provider: "OPENAI", active: true, callsInWindow: 388, cacheReadTokenShare: 0.27, callsWithCacheHitShare: 0.48 },
    { provider: "GOOGLE", active: true, callsInWindow: 0, cacheReadTokenShare: null, callsWithCacheHitShare: null },
  ],
  costAwareRouting: { id: "cost-aware-routing", active: true, description: "Cheaper models answer simple turns; escalation only when confidence drops.", detail: "2 of 4 agents opted in" },
  toolSchemaMinimization: { id: "tool-schema-minimization", active: true, description: "Only the tools an agent may call are sent with each request.", detail: "cap: 24 tools per call" },
  approvalGates: { id: "approval-gates", active: true, description: "High-cost or irreversible actions wait for a human.", detail: "3 approval patterns" },
};

// GET /api/admin/llm-usage/summary (quiet-403 on FREE in real life).
export const llmUsageSummary = { totalCalls: 2_140, successRate: 0.987, totalCostUsd: 9.42, byProvider: [{ provider: "PLATFORM", calls: 1_240, costUsd: 0 }, { provider: "OPENAI", calls: 580, costUsd: 6.1 }, { provider: "ANTHROPIC", calls: 320, costUsd: 3.32 }] };

// GET /api/agents/{id}/tool-calls - ToolCallResponse[] (playground rail).
export const toolCalls = [
  { id: "tc_1", toolName: "orders_lookup", status: "SUCCESS", durationMs: 412, input: '{"orderId":"A-10432"}', output: '{"status":"SHIPPED","eta":"2026-09-25"}', createdAt: iso(41 * MIN) },
  { id: "tc_2", toolName: "whatsapp_send_template", status: "PENDING_APPROVAL", durationMs: 0, input: '{"template":"delivery_delay","count":42}', output: null as string | null, createdAt: iso(35 * MIN) },
  { id: "tc_3", toolName: "gst_amount", status: "SUCCESS", durationMs: 8, input: '{"amount":7250}', output: "1305", createdAt: iso(2 * HOUR) },
];

// Channel credentials (admin "connected channels" card) - null = not set up.
export const twilioCredential = { accountSid: "ACxxxxxxxxxxxxxxxx(sample)", fromNumber: "+91 98xxx xx210", status: "ACTIVE", updatedAt: iso(20 * DAY) };

// GET /api/sdlc/credential/status - E2bCredentialResponse (ProvidersPanel's
// E2B card reads status/createdAt/codeExecutionTemplateId; never the key).
export const e2bCredential = { id: "e2b_1", status: "ACTIVE", createdAt: iso(14 * DAY), updatedAt: iso(2 * DAY), codeExecutionTemplateId: "vegaduta-python-3.12 (sample)" };
