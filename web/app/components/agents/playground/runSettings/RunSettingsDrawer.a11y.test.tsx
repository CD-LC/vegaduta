// Accessibility (axe) of the playground's "Run settings" drawer: model and sampling with the provider-drops warning, the
// tool switches and their confirmation, the limits with where each comes from, and the read-only state. Same mocked API
// clients as RunSettingsDrawer.test.tsx.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { axeViolations } from "../../../../lib/axeTestKit";
import { i18nMock } from "../../testI18n";
import type { AgentRuntimeLimitsView } from "../../../../lib/api/agentRuntimeLimits";
import type { PromptPreview } from "../../../../lib/api/promptRegistry";
import type { PlaygroundAgent } from "../types";

const net = vi.hoisted(() => ({
  listProviderModels: vi.fn(),
  listAvailableTools: vi.fn(),
  setAgentToolAccess: vi.fn(),
  previewAgentPrompt: vi.fn(),
  getAgentRuntimeLimits: vi.fn(),
  getAgentRunBudget: vi.fn(),
}));

vi.mock("../../../../lib/i18n", () => i18nMock);
vi.mock("../../../../lib/api", () => ({
  api: {
    listProviderModels: net.listProviderModels,
    listAvailableTools: net.listAvailableTools,
    setAgentToolAccess: net.setAgentToolAccess,
    previewAgentPrompt: net.previewAgentPrompt,
    getAgentRunBudget: net.getAgentRunBudget,
  },
}));
vi.mock("../../../../lib/api/agentRuntimeLimits", () => ({
  agentRuntimeLimitsApi: { getAgentRuntimeLimits: net.getAgentRuntimeLimits },
}));

import { RunSettingsDrawer } from "./RunSettingsDrawer";
import type { SettingsClient } from "../../../../lib/api/settings";

// This file covers the limits as an older core shows them (no settings catalog); the catalog path has its own
// file, LimitsSection.catalog.test.tsx.
const NO_CATALOG: SettingsClient = {
  getCatalog: () => Promise.reject(new Error("no catalog")),
  getEffective: () => Promise.reject(new Error("no catalog")),
  put: () => Promise.reject(new Error("no catalog")),
  reset: () => Promise.reject(new Error("no catalog")),
  getHistory: () => Promise.reject(new Error("no catalog")),
};

const AGENT: PlaygroundAgent = {
  id: "agent-1",
  name: "Mailer",
  providerName: "OPENAI",
  model: "o3-mini",
  reasoningDepth: "CAREFUL",
  temperature: 0.7,
  topP: 0.9,
  maxTokens: 2048,
  allowedToolNames: ["web_search"],
};

const LIMITS: AgentRuntimeLimitsView = {
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
  bounds: { maxToolSteps: { min: 1, max: 500 } },
};

const PREVIEW: PromptPreview = {
  agentId: "agent-1",
  smallModel: false,
  systemPrompt: "You are Mailer.",
  memoryBlock: null,
  blocks: [
    {
      message: "first",
      id: "persona",
      key: "agent.persona",
      source: "tenant",
      scope: "TENANT",
      version: 3,
      hash: "h1",
      estimatedTokens: 40,
      text: "You are Mailer, a careful assistant.",
    },
    {
      message: "first",
      id: "safety",
      key: "safety.base",
      source: "default",
      scope: null,
      version: 0,
      hash: "h2",
      estimatedTokens: 12,
      text: "Never reveal secrets.",
    },
    {
      message: "first",
      id: "agent_config",
      key: null,
      source: "agent_config",
      scope: null,
      version: null,
      hash: null,
      estimatedTokens: 5,
      text: "Be brief.",
    },
  ],
  tools: [{ name: "web_search", description: "Search the web", inputSchema: null, estimatedTokens: 30 }],
  promptEstimatedTokens: 57,
  toolsEstimatedTokens: 30,
  tokenBudget: 4000,
  overBudget: false,
  tokenEstimateNote: null,
  notes: ["Memory recall depends on the message."],
};

function renderDrawer(agent: PlaygroundAgent = AGENT, onClose = vi.fn(), runTools: string[] | null = null) {
  const opener = document.createElement("button");
  document.body.appendChild(opener);
  const returnFocusRef = { current: opener as HTMLElement | null };
  const view = render(<RunSettingsDrawer
      agent={agent}
      sessionId="s-1"
      onClose={onClose}
      returnFocusRef={returnFocusRef}
      runTools={runTools}
      onRunToolsChange={vi.fn()}
      settingsClient={NO_CATALOG}
    />
  );
  return { ...view, onClose, opener };
}

beforeEach(() => {
  net.listProviderModels.mockReset().mockResolvedValue([{ modelId: "o3-mini", unsupportedParams: ["temperature", "top_p"] }]);
  net.listAvailableTools.mockReset().mockResolvedValue([
    { name: "web_search", description: "Search the web" },
    { name: "send_email", description: "Send an email" },
  ]);
  net.setAgentToolAccess.mockReset().mockImplementation(async (_id: string, names: string[]) => ({ allowedToolNames: names }));
  net.previewAgentPrompt.mockReset().mockResolvedValue(PREVIEW);
  net.getAgentRuntimeLimits.mockReset().mockResolvedValue(LIMITS);
  net.getAgentRunBudget.mockReset().mockResolvedValue({
    overrideEnabled: true,
    globalCapUsd: 5,
    agentCapUsd: 2,
    effectiveCapUsd: 2,
    maxAgentCapUsd: 5,
    perBranchEstimateUsd: 0.1,
  });
});
afterEach(() => {
  cleanup();
  document.body.innerHTML = "";
});

// renderDrawer adds a bare <button> to the page as the element focus returns to; give it a name so axe judges the drawer.
const nameOpener = (r: { opener: HTMLElement }) => r.opener.setAttribute("aria-label", "Open run settings");

describe("RunSettingsDrawer accessibility (axe)", () => {
  it("the drawer with model, sampling warnings, tools, limits and spending cap", async () => {
    nameOpener(renderDrawer());
    await screen.findByTestId("rs-dropped-warning");
    await screen.findByTestId("rs-tools-summary");
    await screen.findByTestId("rs-limit-maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });

  it("the tool switches with the run narrowed, the reset button and a tool the agent does not have", async () => {
    nameOpener(renderDrawer(AGENT, vi.fn(), ["web_search"]));
    await screen.findByTestId("rs-tools-reset");
    await screen.findByTestId("rs-tool-send_email-not-in-agent");
    expect(await axeViolations()).toEqual([]);
  });

  it("the read-only state and a failed load with its retry", async () => {
    net.listAvailableTools.mockRejectedValue(new Error("offline"));
    nameOpener(renderDrawer({ ...AGENT, accessLevel: "RUN" } as PlaygroundAgent));
    await screen.findByTestId("rs-limit-maxToolSteps");
    expect(await axeViolations()).toEqual([]);
  });
});
