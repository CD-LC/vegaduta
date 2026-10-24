// /admin/instructions?agent=<id>: the registry opens at agent scope for a visible agent, falls back to the workspace
// with a notice for one that is missing or hidden, and keeps ?agent= in step with the scope picker.
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
import { pickOption } from "../../components/ui/test-select";

const api = vi.hoisted(() => ({
  listPrompts: vi.fn(),
  getPromptHistory: vi.fn(),
  listAgents: vi.fn(),
  getPromptDirectives: vi.fn(),
}));
const nav = vi.hoisted(() => ({ search: "" }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/admin/instructions",
  useSearchParams: () => new URLSearchParams(nav.search),
}));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/useRequireScope", () => ({ useRequireScope: () => true }));

import AdminInstructionsPage from "./page";
import PromptRegistryPanel from "./PromptRegistryPanel";
import type { PromptView } from "../../lib/api/promptRegistry";

const t = en.promptDirectives;
const AGENTS = [
  { id: "ag-1", name: "Support bot" },
  { id: "ag-2", name: "Sales bot" },
];

function prompt(key: string, title: string): PromptView {
  return {
    definition: {
      key, title, description: "", riskClass: "STEERS_EVERY_TURN", securityCritical: false, disableable: false,
      retired: false, strictPlaceholders: true, placeholders: [], minLength: 0, maxLength: 4000,
    },
    defaultText: "Shipped.",
    effective: {
      key, text: "Shipped.", source: "DEFAULT", scope: null, version: 0, locale: "en", enabled: true, known: true, hash: "h", estimatedTokens: 2,
    },
    overrides: [],
  } as PromptView;
}

// The distinct requests made for prompts (the stand-in translator is not referentially stable, which makes the panel
// reload on every render; what matters here is which agents were ever asked for, in order).
function promptCalls(): unknown[] {
  const seen = new Set<string>();
  return api.listPrompts.mock.calls
    .map((c) => c[0])
    .filter((c) => {
      const k = JSON.stringify(c);
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
}

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
  api.listPrompts.mockResolvedValue([prompt("directive.current_date", "Current date")]);
  api.getPromptHistory.mockResolvedValue([]);
  api.listAgents.mockResolvedValue(AGENTS);
  api.getPromptDirectives.mockResolvedValue({
    directives: [], customInstructions: { enabled: true, text: "" }, assembledPreview: "", assembledPreviewNote: null,
  });
  nav.search = "";
  window.history.replaceState(null, "", "/admin/instructions");
});
afterEach(cleanup);

describe("registry deep link: ?agent=", () => {
  it("opens at agent scope for a visible agent and loads that agent's prompts", async () => {
    window.history.replaceState(null, "", "/admin/instructions?agent=ag-2");
    render(<PromptRegistryPanel initialAgentId="ag-2" />);
    await screen.findByText("Current date");
    expect(promptCalls()).toEqual([{ agentId: "ag-2" }]);
    expect(screen.getByRole("combobox", { name: t.regScopeLabel }).textContent).toContain(t.regScope_agent);
    expect(screen.getByRole("combobox", { name: t.regAgentLabel }).textContent).toContain("Sales bot");
    expect(screen.queryByTestId("registry-agent-unavailable")).toBeNull();
    expect(window.location.search).toBe("?agent=ag-2");
  });

  it("falls back to the workspace with a notice when the agent is not visible, and never asks for its prompts", async () => {
    window.history.replaceState(null, "", "/admin/instructions?agent=hidden-9&keep=1");
    render(<PromptRegistryPanel initialAgentId="hidden-9" />);
    const notice = await screen.findByTestId("registry-agent-unavailable");
    expect(notice.textContent).toContain(t.regAgentUnavailable);
    await screen.findByText("Current date");
    expect(promptCalls()).toEqual([{ agentId: null }]);
    expect(screen.getByRole("combobox", { name: t.regScopeLabel }).textContent).toContain(t.regScope_tenant);
    await waitFor(() => expect(window.location.search).toBe("?keep=1"));
  });

  it("falls back to the workspace when the agent list cannot be read", async () => {
    api.listAgents.mockRejectedValue(new Error("agents down"));
    render(<PromptRegistryPanel initialAgentId="ag-1" />);
    expect(await screen.findByText("agents down")).toBeTruthy();
    await waitFor(() => expect(api.listPrompts).toHaveBeenCalledWith({ agentId: null }));
    expect(api.listPrompts).not.toHaveBeenCalledWith({ agentId: "ag-1" });
  });

  it("keeps ?agent= in step with the scope picker", async () => {
    render(<PromptRegistryPanel />);
    await screen.findByText("Current date");
    expect(window.location.search).toBe("");

    pickOption(t.regScopeLabel, t.regScope_agent);
    await waitFor(() => expect(api.listAgents).toHaveBeenCalled());
    expect(window.location.search).toBe("");
    pickOption(t.regAgentLabel, "Support bot");
    await waitFor(() => expect(window.location.search).toBe("?agent=ag-1"));
    pickOption(t.regAgentLabel, "Sales bot");
    await waitFor(() => expect(window.location.search).toBe("?agent=ag-2"));
    pickOption(t.regScopeLabel, t.regScope_tenant);
    await waitFor(() => expect(window.location.search).toBe(""));
  });

  it("does not touch the address when no agent was asked for", async () => {
    window.history.replaceState(null, "", "/admin/instructions?tab=x");
    render(<PromptRegistryPanel />);
    await screen.findByText("Current date");
    expect(window.location.search).toBe("?tab=x");
  });
});

describe("instructions page with ?agent=", () => {
  it("opens the Prompt registry tab at that agent's scope", async () => {
    nav.search = "agent=ag-1";
    window.history.replaceState(null, "", "/admin/instructions?agent=ag-1");
    render(<AdminInstructionsPage />);
    await screen.findByText("Current date");
    expect(screen.getByRole("tab", { name: t.tabRegistry })).toHaveAttribute("aria-selected", "true");
    expect(api.listPrompts).toHaveBeenCalledWith({ agentId: "ag-1" });
  });

  it("ignores a malformed ?agent= and opens the usual first tab", async () => {
    nav.search = `agent=${encodeURIComponent("<script>")}`;
    render(<AdminInstructionsPage />);
    await screen.findByRole("tab", { name: t.tabEdit });
    expect(screen.getByRole("tab", { name: t.tabEdit })).toHaveAttribute("aria-selected", "true");
    expect(api.listPrompts).not.toHaveBeenCalled();
  });

  it("opens the usual first tab without ?agent=", async () => {
    render(<AdminInstructionsPage />);
    await screen.findByRole("tab", { name: t.tabEdit });
    expect(screen.getByRole("tab", { name: t.tabEdit })).toHaveAttribute("aria-selected", "true");
  });
});
