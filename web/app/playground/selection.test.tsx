// Prod signed-in pass 2026-10-05, bug 10: the Playground forgot the selected
// agent on reload, so a resumed (resumable-stream) reply only appeared after
// the person reselected the agent they had been talking to.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import {
  PLAYGROUND_AGENT_STORAGE_KEY,
  initialAgentId,
  persistAgentSelection,
  readStoredAgentId,
  resolveAgentId,
  withAgentParam,
} from "./selection";

const AGENTS = [
  { id: "a-first", name: "Comparison Agent", providerName: "OPENROUTER" },
  { id: "a-second", name: "e2e-agent", providerName: "OPENROUTER" },
];

describe("playground selection - pure rules", () => {
  beforeEach(() => window.localStorage.clear());

  it("initial id: the URL wins, then the last agent used in this browser", () => {
    expect(initialAgentId(null)).toBe("");
    window.localStorage.setItem(PLAYGROUND_AGENT_STORAGE_KEY, "a-second");
    expect(initialAgentId(null)).toBe("a-second");
    expect(initialAgentId("a-first")).toBe("a-first");
  });

  it("resolve: URL agent, then the current selection, then the first agent", () => {
    expect(resolveAgentId(AGENTS, "a-second", "a-first")).toBe("a-second");
    expect(resolveAgentId(AGENTS, null, "a-second")).toBe("a-second");
    expect(resolveAgentId(AGENTS, null, "")).toBe("a-first");
  });

  it("resolve: never keeps an id that is not in the list", () => {
    expect(resolveAgentId(AGENTS, "deleted", "also-deleted")).toBe("a-first");
    expect(resolveAgentId([], "a-first", "a-second")).toBe("");
  });

  it("withAgentParam keeps other params and the hash", () => {
    expect(withAgentParam("https://app.example/playground?x=1#top", "a-second")).toBe(
      "/playground?x=1&agent=a-second#top"
    );
    expect(withAgentParam("/playground?agent=a-first", "")).toBe("/playground");
  });

  it("persist writes localStorage and the address bar; blocked storage does not throw", () => {
    window.history.replaceState(null, "", "/playground");
    persistAgentSelection("a-second");
    expect(readStoredAgentId()).toBe("a-second");
    expect(window.location.search).toBe("?agent=a-second");

    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => persistAgentSelection("a-first")).not.toThrow();
    expect(readStoredAgentId()).toBe("");
    expect(window.location.search).toBe("?agent=a-first"); // the URL still carries it
    setItem.mockRestore();
    getItem.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// The page: a reload must come back on the same agent, on the FIRST agent the
// chat panel is ever given (that is when its resume check runs).
// ---------------------------------------------------------------------------

const search = { value: "" };
const chatPanelAgents: Array<string | null> = [];

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(search.value),
}));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));
vi.mock("../lib/api", () => ({ api: { listAgents: vi.fn(() => Promise.resolve(AGENTS)) } }));
vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../components/ax/PageHeader", () => ({ default: (): null => null }));
vi.mock("../components/PoweredByChip", () => ({ default: (): null => null, inferSource: () => "byok" }));
vi.mock("../components/Select", () => ({
  default: ({ value, onChange, options }: { value: string; onChange: (e: { target: { value: string } }) => void; options: Array<{ value: string; label: string }> }) => (
    <select data-testid="agent-select" value={value} onChange={(e) => onChange({ target: { value: e.target.value } })}>
      {options.map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));
vi.mock("../components/ChatPanel", () => ({
  default: ({ agent }: { agent: { id: string } | null }) => {
    chatPanelAgents.push(agent ? agent.id : null);
    return <div data-testid="chat">{agent ? agent.id : "none"}</div>;
  },
}));

describe("playground page - the selected agent survives a reload", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.history.replaceState(null, "", "/playground");
    search.value = "";
    chatPanelAgents.length = 0;
  });
  afterEach(cleanup);

  it("restores the last-used agent on a bare /playground reload, without passing through the first agent", async () => {
    window.localStorage.setItem(PLAYGROUND_AGENT_STORAGE_KEY, "a-second");
    const { default: PlaygroundPage } = await import("./PlaygroundLegacy");
    render(<PlaygroundPage />);

    await waitFor(() => expect(screen.getByTestId("chat").textContent).toBe("a-second"));
    // The chat panel (whose mount runs the resume check) never saw "a-first".
    expect(chatPanelAgents).not.toContain("a-first");
    // The effect that mirrors the selection runs just after the chat panel renders.
    await waitFor(() => expect(window.location.search).toBe("?agent=a-second"));
  });

  it("?agent= in the URL wins over the stored agent", async () => {
    window.localStorage.setItem(PLAYGROUND_AGENT_STORAGE_KEY, "a-first");
    search.value = "agent=a-second";
    const { default: PlaygroundPage } = await import("./PlaygroundLegacy");
    render(<PlaygroundPage />);
    await waitFor(() => expect(screen.getByTestId("chat").textContent).toBe("a-second"));
    expect(chatPanelAgents).not.toContain("a-first");
  });

  it("picking an agent writes it to the URL and localStorage", async () => {
    const { default: PlaygroundPage } = await import("./PlaygroundLegacy");
    const { fireEvent } = await import("@testing-library/react");
    render(<PlaygroundPage />);
    await waitFor(() => expect(screen.getByTestId("chat").textContent).toBe("a-first"));

    fireEvent.change(screen.getByTestId("agent-select"), { target: { value: "a-second" } });

    await waitFor(() => expect(window.location.search).toBe("?agent=a-second"));
    expect(readStoredAgentId()).toBe("a-second");
  });

  it("falls back to the first agent when the stored one no longer exists", async () => {
    window.localStorage.setItem(PLAYGROUND_AGENT_STORAGE_KEY, "deleted-agent");
    const { default: PlaygroundPage } = await import("./PlaygroundLegacy");
    render(<PlaygroundPage />);
    await waitFor(() => expect(screen.getByTestId("chat").textContent).toBe("a-first"));
  });
});
