// Playground run controls - sample answers for the dev-only preview fetch mock (app/ui-kit/dashboard-preview/
// mock-routes.ts spreads these into its patternRoutes). They answer the three reads the "Run settings" drawer
// makes - the agent's available tools, its runtime limits and the prompt preview - so the drawer can be looked
// at with no backend. Types only; never imported by the playground itself.

type Handler = (m: RegExpMatchArray, url: URL, init?: RequestInit) => { status?: number; body: unknown } | null;

const availableTools: Handler = () => ({
  body: [
    { name: "web_search", description: "Search the web and return the top results." },
    { name: "send_email", description: "Send an email from the agent's mailbox." },
    { name: "search_knowledge", description: "Search the agent's knowledge base." },
  ],
});

const runtimeLimits: Handler = (_m, _url, init) =>
  (init?.method || "GET").toUpperCase() === "GET"
    ? {
        body: {
          overrides: { maxToolSteps: 12 },
          effective: {
            maxToolSteps: 12,
            turnTimeoutSeconds: 300,
            toolTimeoutSeconds: 0,
            toolOutputMaxChars: 8000,
            retrievalTopK: 5,
            retrievalMinScore: 0.25,
            continuationPasses: 1,
            layers: { maxToolSteps: "AGENT", turnTimeoutSeconds: "TENANT" },
          },
          bounds: { maxToolSteps: { min: 1, max: 500 }, turnTimeoutSeconds: { min: 10, max: 3600 } },
        },
      }
    : null;

const promptPreview: Handler = (m) =>
  ({
    body: {
      agentId: m[1],
      smallModel: false,
      systemPrompt: "You are a careful support agent.",
      memoryBlock: null,
      blocks: [
        {
          message: "Every turn",
          id: "persona",
          key: "agent.persona",
          source: "tenant",
          scope: "TENANT",
          version: 3,
          hash: "a1",
          estimatedTokens: 64,
          text: "You are a careful support agent for Acme. Answer in the customer's language.",
        },
        {
          message: "Every turn",
          id: "safety",
          key: "safety.base",
          source: "default",
          scope: null,
          version: 0,
          hash: "b2",
          estimatedTokens: 28,
          text: "Never reveal credentials. Decline requests that break the rules above.",
        },
        {
          message: "Every turn",
          id: "agent_config",
          key: null,
          source: "agent_config",
          scope: null,
          version: null,
          hash: null,
          estimatedTokens: 18,
          text: "Keep answers short.",
        },
      ],
      tools: [{ name: "web_search", description: "Search the web.", inputSchema: null, estimatedTokens: 90 }],
      promptEstimatedTokens: 110,
      toolsEstimatedTokens: 90,
      tokenBudget: 4000,
      overBudget: false,
      tokenEstimateNote: "Token counts are estimates.",
      notes: ["Recalled memory depends on the actual message."],
    },
  });

export const runControlsMockPatternRoutes: Array<[RegExp, Handler]> = [
  [/^\/api\/agents\/([^/]+)\/available-tools$/, availableTools],
  [/^\/api\/agents\/([^/]+)\/runtime-limits$/, runtimeLimits],
  [/^\/api\/agents\/([^/]+)\/prompt-preview$/, promptPreview],
];
