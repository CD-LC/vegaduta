// The playground's "Run settings" drawer, rendered for real: model and sampling (with the provider-drops warning),
// the tool switches (this run only: nothing is saved to the agent), the limits with where each comes
// from, and the prompt preview by block. Only the API clients are mocked.

import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

function renderDrawer(agent: PlaygroundAgent = AGENT, onClose = vi.fn(), initialRunTools: string[] | null = null) {
  const opener = document.createElement("button");
  document.body.appendChild(opener);
  const returnFocusRef = { current: opener as HTMLElement | null };
  const onRunToolsChange = vi.fn();
  // The playground owns the selection; this host does the same so the switches can be driven for real.
  function Host() {
    const [runTools, setRunTools] = useState<string[] | null>(initialRunTools);
    return (
      <RunSettingsDrawer
        agent={agent}
        sessionId="s-1"
        onClose={onClose}
        returnFocusRef={returnFocusRef}
        runTools={runTools}
        onRunToolsChange={(names) => {
          onRunToolsChange(names);
          setRunTools(names);
        }}
        settingsClient={NO_CATALOG}
      />
    );
  }
  const view = render(<Host />);
  return { ...view, onClose, opener, onRunToolsChange };
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

describe("RunSettingsDrawer - model and sampling", () => {
  it("shows the model, the depth in plain words and the sampling values", async () => {
    renderDrawer();
    expect(screen.getByRole("dialog", { name: "Run settings" })).toBeTruthy();
    expect(within(screen.getByTestId("rs-model")).getByText("OPENAI / o3-mini")).toBeTruthy();
    expect(within(screen.getByTestId("rs-depth")).getByText("Careful")).toBeTruthy();
    expect(within(screen.getByTestId("rs-temperature")).getByText("0.7")).toBeTruthy();
    expect(within(screen.getByTestId("rs-maxTokens")).getByText("2,048")).toBeTruthy();
    expect(screen.getByTestId("rs-depth-hint").textContent).toContain("Double-checks answers");
    await screen.findByTestId("rs-dropped-warning");
  });

  it("warns for each set parameter the provider drops for this model, and only those", async () => {
    renderDrawer();
    const warning = await screen.findByTestId("rs-dropped-warning");
    expect(warning.textContent).toContain("Temperature is set on this agent, but OPENAI ignores it for o3-mini");
    expect(warning.textContent).toContain("Top-P is set on this agent");
    expect(warning.textContent).not.toContain("Max reply length");
  });

  it("shows no warning when the catalog does not say anything", async () => {
    net.listProviderModels.mockResolvedValue([{ modelId: "o3-mini" }]);
    renderDrawer();
    await waitFor(() => expect(net.listProviderModels).toHaveBeenCalled());
    expect(screen.queryByTestId("rs-dropped-warning")).toBeNull();
  });

  it("says 'model default' for a setting that is not set", () => {
    renderDrawer({ ...AGENT, temperature: null });
    expect(within(screen.getByTestId("rs-temperature")).getByText("Model default")).toBeTruthy();
  });

  it("links to the Behavior tab", () => {
    renderDrawer();
    const link = screen.getByRole("link", { name: "Edit in Behavior" });
    expect(link.getAttribute("href")).toBe("/agents?agentId=agent-1&view=workbench&section=behavior");
  });
});

describe("RunSettingsDrawer - tools (this run only)", () => {
  it("lists every available tool, on when the saved agent has it", async () => {
    renderDrawer();
    const list = await screen.findByTestId("rs-tools-summary");
    expect(list.textContent).toBe("1 of 2 tools on");
    expect(screen.getByLabelText("web_search").getAttribute("aria-checked")).toBe("true");
    expect(screen.getByLabelText("send_email").getAttribute("aria-checked")).toBe("false");
  });

  it("is labelled 'This run only' and says nothing is saved", async () => {
    renderDrawer();
    await screen.findByTestId("rs-tools-summary");
    expect(screen.getByTestId("rs-tools-run-only").textContent).toBe("This run only");
    expect(screen.getByTestId("run-settings-tools").textContent).toContain("Nothing is saved");
  });

  it("turning a tool off changes the run's selection and writes nothing to the agent: no dialog, no request", async () => {
    const { onRunToolsChange } = renderDrawer({ ...AGENT, allowedToolNames: [] });
    fireEvent.click(await screen.findByLabelText("web_search"));
    expect(onRunToolsChange).toHaveBeenCalledWith(["send_email"]);
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(net.setAgentToolAccess).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByLabelText("web_search").getAttribute("aria-checked")).toBe("false"));
    expect(screen.getByLabelText("send_email").getAttribute("aria-checked")).toBe("true");
    expect(screen.getByTestId("rs-tools-summary").textContent).toBe("1 of the agent's 2 tools on for this run");
  });

  it("an unrestricted agent shows every tool on, and the first switch off leaves the others selected", async () => {
    const { onRunToolsChange } = renderDrawer({ ...AGENT, allowedToolNames: [] });
    expect((await screen.findByTestId("rs-tools-summary")).textContent).toBe("2 of 2 tools on");
    expect(screen.getByLabelText("send_email").getAttribute("aria-checked")).toBe("true");
    fireEvent.click(screen.getByLabelText("send_email"));
    expect(onRunToolsChange).toHaveBeenCalledWith(["web_search"]);
  });

  it("can switch every tool off for a run (a run without tools), unlike the saved agent", async () => {
    const { onRunToolsChange } = renderDrawer(); // the agent has only web_search
    fireEvent.click(await screen.findByLabelText("web_search"));
    expect(onRunToolsChange).toHaveBeenCalledWith([]);
    expect(await screen.findByTestId("rs-tools-none")).toBeTruthy();
    expect(net.setAgentToolAccess).not.toHaveBeenCalled();
  });

  it("cannot switch on a tool the saved agent does not have", async () => {
    const { onRunToolsChange } = renderDrawer();
    const sw = (await screen.findByLabelText("send_email")) as HTMLButtonElement;
    expect(sw.disabled).toBe(true);
    expect(screen.getByTestId("rs-tool-send_email-not-in-agent").textContent).toContain("Not one of this agent's saved tools");
    fireEvent.click(sw);
    expect(onRunToolsChange).not.toHaveBeenCalled();
  });

  it("turning the tool back on returns to no selection", async () => {
    const { onRunToolsChange } = renderDrawer({ ...AGENT, allowedToolNames: [] });
    fireEvent.click(await screen.findByLabelText("web_search"));
    await waitFor(() => expect(screen.getByTestId("rs-tools-reset")).toBeTruthy());
    fireEvent.click(screen.getByLabelText("web_search"));
    expect(onRunToolsChange).toHaveBeenLastCalledWith(null);
    await waitFor(() => expect(screen.queryByTestId("rs-tools-reset")).toBeNull());
  });

  it("shows a reset only while the run is narrowed, and it goes back to the agent's own tools", async () => {
    const { onRunToolsChange } = renderDrawer({ ...AGENT, allowedToolNames: [] }, vi.fn(), ["web_search"]);
    expect((await screen.findByTestId("rs-tools-summary")).textContent).toBe("1 of the agent's 2 tools on for this run");
    fireEvent.click(screen.getByTestId("rs-tools-reset"));
    expect(onRunToolsChange).toHaveBeenCalledWith(null);
    await waitFor(() => expect(screen.getByTestId("rs-tools-summary").textContent).toBe("2 of 2 tools on"));
    expect(screen.queryByTestId("rs-tools-reset")).toBeNull();
    expect(net.setAgentToolAccess).not.toHaveBeenCalled();
  });

  it("anyone who can run the agent can narrow a run, it changes nothing for anyone else", async () => {
    const { onRunToolsChange } = renderDrawer({ ...AGENT, allowedToolNames: [], accessLevel: "RUNNER" });
    fireEvent.click(await screen.findByLabelText("send_email"));
    expect(onRunToolsChange).toHaveBeenCalledWith(["web_search"]);
    expect(screen.queryByTestId("rs-tools-edit-agent")).toBeNull();
  });

  it("links an editor to where the saved tools are changed", async () => {
    renderDrawer();
    const link = await screen.findByTestId("rs-tools-edit-agent");
    expect(link.getAttribute("href")).toBe("/agents?agentId=agent-1&view=workbench");
  });

  it("shows an empty state and a retry on a failed load", async () => {
    net.listAvailableTools.mockRejectedValueOnce(new Error("MCP server timed out"));
    renderDrawer();
    expect((await screen.findByText("MCP server timed out")).closest("[role=alert]")).toBeTruthy();
    net.listAvailableTools.mockResolvedValueOnce([]);
    fireEvent.click(within(screen.getByTestId("run-settings-tools")).getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("rs-tools-empty")).toBeTruthy();
  });
});

describe("RunSettingsDrawer - limits", () => {
  it("shows each limit with its unit and where it comes from", async () => {
    renderDrawer();
    const steps = await screen.findByTestId("rs-limit-maxToolSteps");
    expect(within(steps).getByText("Tool calls per turn")).toBeTruthy();
    expect(within(steps).getByText("12")).toBeTruthy();
    expect(within(steps).getByTestId("rs-limit-maxToolSteps-layer").textContent).toBe("Set on this agent");
    expect(within(steps).getByText("Allowed: 1 to 500")).toBeTruthy();
    expect(within(screen.getByTestId("rs-limit-turnTimeoutSeconds")).getByText("300 s")).toBeTruthy();
    expect(within(screen.getByTestId("rs-limit-turnTimeoutSeconds-layer")).getByText).toBeTruthy();
    expect(screen.getByTestId("rs-limit-turnTimeoutSeconds-layer").textContent).toBe("Workspace default");
    // A timeout of 0 means no limit, and an unreported layer is the platform's.
    const tool = screen.getByTestId("rs-limit-toolTimeoutSeconds");
    expect(within(tool).getByText("No limit")).toBeTruthy();
    expect(screen.getByTestId("rs-limit-toolTimeoutSeconds-layer").textContent).toBe("Platform default");
    expect(within(screen.getByTestId("rs-limit-toolOutputMaxChars")).getByText("8,000 characters")).toBeTruthy();
  });

  it("shows the spending cap per run with where it comes from", async () => {
    renderDrawer();
    const row = await screen.findByTestId("rs-limit-runBudget");
    expect(within(row).getByText("Spending cap per run")).toBeTruthy();
    expect(within(row).getByText("$2.00")).toBeTruthy();
    expect(within(row).getByTestId("rs-limit-runBudget-layer").textContent).toBe("Set on this agent");
  });

  it("leaves the spending cap out when it cannot be read, without an error", async () => {
    net.getAgentRunBudget.mockRejectedValue(new Error("not found"));
    renderDrawer();
    await screen.findByTestId("rs-limit-maxToolSteps");
    expect(screen.queryByTestId("rs-limit-runBudget")).toBeNull();
    expect(screen.queryByText("not found")).toBeNull();
  });

  it("links an editor to the limits and a viewer to the same place read-only", async () => {
    const { unmount } = renderDrawer();
    expect((await screen.findByTestId("rs-limits-link")).textContent).toBe("Change limits");
    expect(screen.getByTestId("rs-limits-link").getAttribute("href")).toContain("focus=agent.limits");
    unmount();
    renderDrawer({ ...AGENT, accessLevel: "VIEWER" });
    expect((await screen.findByTestId("rs-limits-link")).textContent).toBe("See limits");
  });

  it("shows the error and recovers on retry", async () => {
    net.getAgentRuntimeLimits.mockRejectedValueOnce(new Error("You do not have access to this agent."));
    renderDrawer();
    expect(await screen.findByText("You do not have access to this agent.")).toBeTruthy();
    fireEvent.click(within(screen.getByTestId("run-settings-limits")).getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("rs-limit-maxToolSteps")).toBeTruthy();
  });
});

describe("RunSettingsDrawer - prompt preview", () => {
  it("builds the preview on demand and shows each block with its source and version", async () => {
    renderDrawer();
    expect(net.previewAgentPrompt).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("rs-preview-button"));
    expect(await screen.findByTestId("rs-preview-result")).toBeTruthy();
    expect(net.previewAgentPrompt).toHaveBeenCalledWith("agent-1", { sessionId: "s-1" });
    expect(screen.getByRole("heading", { name: "Prompt preview" })).toBeTruthy();

    const persona = screen.getByTestId("rs-block-persona");
    expect(persona.textContent).toContain("agent.persona");
    expect(persona.textContent).toContain("tenant override v3");
    expect(screen.getByTestId("rs-block-safety").textContent).toContain("shipped default");
    expect(screen.getByTestId("rs-block-agent_config").textContent).toContain("agent's own system prompt");
    expect(screen.getByText("About 57 tokens of system text and 30 tokens of tool definitions (estimates).")).toBeTruthy();
    expect(screen.getByTestId("rs-preview-tool-web_search").textContent).toContain("Search the web");
    expect(screen.getByText("Memory recall depends on the message.")).toBeTruthy();
    expect(screen.getByRole("link", { name: /change the wording/ }).getAttribute("href")).toBe("/admin/instructions");
  });

  it("warns when the system text is over budget", async () => {
    net.previewAgentPrompt.mockResolvedValue({ ...PREVIEW, overBudget: true, tokenBudget: 50 });
    renderDrawer();
    fireEvent.click(screen.getByTestId("rs-preview-button"));
    expect(await screen.findByText("Over the token budget")).toBeTruthy();
  });

  it("explains a failure and offers a retry", async () => {
    net.previewAgentPrompt.mockRejectedValueOnce(new Error("Preview needs an editor."));
    renderDrawer();
    fireEvent.click(screen.getByTestId("rs-preview-button"));
    expect((await screen.findByTestId("rs-preview-error")).textContent).toContain("Preview needs an editor.");
    fireEvent.click(within(screen.getByTestId("rs-preview-error")).getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("rs-preview-result")).toBeTruthy();
  });

  it("goes back to the settings with the back button", async () => {
    renderDrawer();
    fireEvent.click(screen.getByTestId("rs-preview-button"));
    await screen.findByTestId("rs-preview-result");
    fireEvent.click(screen.getByTestId("rs-back"));
    expect(screen.getByRole("heading", { name: "Run settings" })).toBeTruthy();
    expect(screen.getByTestId("run-settings-model")).toBeTruthy();
  });
});

describe("RunSettingsDrawer - keyboard and focus", () => {
  it("focuses its heading on open, closes on Escape and gives focus back to the opener", () => {
    const { onClose, opener, unmount } = renderDrawer();
    expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Run settings" }));
    fireEvent.keyDown(screen.getByTestId("run-settings-drawer"), { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    unmount();
    expect(document.activeElement).toBe(opener);
  });

  it("Escape in the preview goes back to the settings first", async () => {
    const { onClose } = renderDrawer();
    fireEvent.click(screen.getByTestId("rs-preview-button"));
    await screen.findByTestId("rs-preview-result");
    fireEvent.keyDown(screen.getByTestId("run-settings-drawer"), { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Run settings" })).toBeTruthy();
  });

  it("closes with the close button, which has an accessible name", () => {
    const { onClose } = renderDrawer();
    fireEvent.click(screen.getByRole("button", { name: "Close run settings" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
