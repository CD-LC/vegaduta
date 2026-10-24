// Accessibility (axe) of the prompt registry console: the list, an open editor, the version history with a diff and the
// final-prompt preview. Same mocked API and real English messages as promptRegistry.test.tsx.
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
import { axeViolations } from "../../lib/axeTestKit";
import { pickOption } from "../../components/ui/test-select";

const api = vi.hoisted(() => ({
  listPrompts: vi.fn(),
  getPromptHistory: vi.fn(),
  validatePrompt: vi.fn(),
  savePromptOverride: vi.fn(),
  revertPromptOverride: vi.fn(),
  restorePromptVersion: vi.fn(),
  previewAgentPrompt: vi.fn(),
  listAgents: vi.fn(),
}));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import PromptRegistryPanel from "./PromptRegistryPanel";
import PromptPreviewPanel from "./PromptPreviewPanel";

const t = en.promptDirectives;

const KEY = "directive.current_date";
const prompt = {
  definition: {
    key: KEY,
    title: "Current date",
    description: "Tells the model today's date.",
    riskClass: "STEERS_EVERY_TURN",
    securityCritical: false,
    disableable: true,
    retired: false,
    strictPlaceholders: true,
    placeholders: [{ name: "date", description: "Today in UTC", required: true }],
    minLength: 0,
    maxLength: 4000,
  },
  defaultText: "Today is {date}.",
  effective: { key: KEY, text: "Today is {date}. Be exact.", source: "TENANT", scope: "TENANT", version: 3, locale: "en", enabled: true, known: true, hash: "h3", estimatedTokens: 7 },
  overrides: [{ scope: "TENANT", scopeId: "", locale: "en", text: "Today is {date}. Be exact.", enabled: true, version: 3, createdBy: "ada", createdAt: "2026-10-20T10:00:00Z" }],
};
const secret = {
  ...prompt,
  definition: { ...prompt.definition, key: "directive.tool_trust", title: "Tool trust", securityCritical: true, placeholders: [] as typeof prompt.definition.placeholders },
  effective: { ...prompt.effective, key: "directive.tool_trust", source: "DEFAULT", scope: null as string | null, version: 0 },
  overrides: [] as typeof prompt.overrides,
};
const history = [
  { id: 12, scope: "TENANT", scopeId: "", key: KEY, locale: "en", version: 3, action: "UPDATE", text: "Today is {date}. Be exact.", enabled: true, previousText: "Today is {date}.", previousEnabled: true, reason: "tighter", createdBy: "ada", createdAt: "2026-10-20T10:00:00Z" },
  { id: 5, scope: "TENANT", scopeId: "", key: KEY, locale: "en", version: 1, action: "CREATE", text: "Date: {date}", enabled: true, previousText: null, previousEnabled: true, reason: null, createdBy: "ada", createdAt: "2026-10-01T10:00:00Z" },
];
const preview = {
  agentId: "agent-1",
  smallModel: false,
  systemPrompt: "You are Ada.",
  memoryBlock: "Today is 2026-10-23.",
  blocks: [
    { message: "system", id: "agent.system_prompt", key: null, source: "agent_config", scope: null, version: null, hash: null, estimatedTokens: 5, text: "You are Ada." },
    { message: "memory", id: KEY, key: KEY, source: "tenant", scope: "tenant", version: 3, hash: "h3", estimatedTokens: 9, text: "Today is 2026-10-23." },
  ],
  tools: [{ name: "web_search", description: "Search the web", inputSchema: "{}", estimatedTokens: 40 }],
  promptEstimatedTokens: 14,
  toolsEstimatedTokens: 40,
  tokenBudget: 20,
  overBudget: true,
  tokenEstimateNote: "Estimates use 4 characters per token.",
  notes: ["No sessionId: the history is not replayed."],
};

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
  api.listPrompts.mockResolvedValue([prompt, secret]);
  api.validatePrompt.mockResolvedValue({ valid: true, problems: [], securityCritical: false, requiresAcknowledgement: false, length: 10 });
  api.getPromptHistory.mockResolvedValue(history);
  api.listAgents.mockResolvedValue([{ id: "agent-1", name: "Support bot" }]);
  api.previewAgentPrompt.mockResolvedValue(preview);
});
afterEach(cleanup);

describe("prompt registry accessibility (axe)", () => {
  it("the list of prompts", async () => {
    render(<PromptRegistryPanel />);
    await screen.findByText("Current date");
    expect(await axeViolations()).toEqual([]);
  });

  it("an open editor for an overridden prompt and for a security-critical one", async () => {
    render(<PromptRegistryPanel />);
    await screen.findByText("Current date");
    fireEvent.click(screen.getByRole("button", { name: t.regOpenEditor.replace("{title}", "Current date") }));
    const editor = screen.getByTestId(`editor-${KEY}`);
    expect(editor).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: t.regOpenEditor.replace("{title}", "Tool trust") }));
    expect(screen.getByTestId("editor-directive.tool_trust")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("the version history with a diff open", async () => {
    render(<PromptRegistryPanel />);
    await screen.findByText("Current date");
    fireEvent.click(screen.getByRole("button", { name: t.regOpenEditor.replace("{title}", "Current date") }));
    fireEvent.click(within(screen.getByTestId(`editor-${KEY}`)).getByRole("button", { name: t.regShowHistory }));
    const list = await screen.findByTestId(`history-${KEY}`);
    await within(list).findByText("Version 3");
    expect(await axeViolations()).toEqual([]);
    const diff = within(list).queryAllByRole("button", { name: /^Show changes in version/ });
    expect(diff.length).toBeGreaterThan(0);
    fireEvent.click(diff[0]);
    expect(await axeViolations()).toEqual([]);
  });

  it("the final-prompt preview with its over-budget warning", async () => {
    render(<PromptPreviewPanel />);
    await waitFor(() => expect(api.listAgents).toHaveBeenCalled());
    pickOption(t.regAgentLabel, "Support bot");
    fireEvent.change(screen.getByLabelText(t.regPreviewMessageLabel), { target: { value: "hello" } });
    fireEvent.click(screen.getByRole("button", { name: t.regPreviewBuild }));
    await screen.findByTestId(`block-${KEY}`);
    expect(await axeViolations()).toEqual([]);
  });
});
