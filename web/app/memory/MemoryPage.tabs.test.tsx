// The /memory page as a person meets it: five tabs with counts, a one-line purpose, the URL hash,
// the "notes are not being read" nudge, and unsaved drafts surviving a tab switch.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>): string => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
  useLocale: () => "en-GB",
}));
vi.mock("../components/AppShell", () => ({ default: ({ children }: any): React.ReactElement => <div>{children}</div> }));
vi.mock("../components/ax/PageHeader", () => ({ default: ({ title }: any) => <h1>{title}</h1> }));
vi.mock("../components/RouteTransition", () => ({ usePageReady: () => {} }));
vi.mock("../lib/academyUrl", () => ({
  academyHref: () => "",
  academyUrl: () => "",
  hasAcademy: () => false,
}));
vi.mock("./ImportChatsCard", () => ({ default: () => <div>import-chats-card</div> }));
vi.mock("./ImportMemoryCard", () => ({ default: () => <div>import-memory-card</div> }));
vi.mock("./DangerZone", () => ({ default: () => <div>danger-zone</div> }));
vi.mock("./AddFactForm", () => ({ default: () => <div>add-fact-form</div> }));
vi.mock("./AgentsUsingNotes", () => ({ default: () => <div>agents-using-notes</div> }));

const api = vi.hoisted(() => ({
  getMemory: vi.fn(),
  listAgents: vi.fn(),
  saveMemoryBlock: vi.fn(),
  deleteMemoryBlock: vi.fn(),
  editMemoryFact: vi.fn(),
  deleteMemoryFact: vi.fn(),
}));
vi.mock("../lib/api", () => ({ api }));

import MemoryPage from "./page";

function overview(over: Record<string, unknown> = {}) {
  return {
    available: true,
    blocksEnabled: true,
    selfServiceEnabled: true,
    activeProjectEnabled: false,
    blocks: [{ label: "persona", content: "Be brief" }],
    facts: [
      { id: "f1", scope: "shared", content: "likes tea", createdAt: "2026-09-01T00:00:00Z", lastUsedAt: null as string | null, useCount: 0 },
      { id: "f2", scope: "shared", content: "uses Mac", createdAt: "2026-09-02T00:00:00Z", lastUsedAt: null as string | null, useCount: 0 },
    ],
    limits: { maxCharsPerBlock: 1200, maxProjectBlocks: 20, maxFactChars: 2000 },
    ...over,
  };
}

const AGENTS = [
  { id: "a1", name: "Alpha", shareMemoryWithTenant: false, editable: true },
  { id: "a2", name: "Beta", shareMemoryWithTenant: false, editable: true },
];

async function renderPage(data = overview(), agents: any[] = AGENTS) {
  api.getMemory.mockResolvedValue(data);
  api.listAgents.mockResolvedValue(agents);
  render(<MemoryPage />);
  await screen.findByRole("tablist");
}

const tabNames = () => screen.getAllByRole("tab").map((t) => (t.textContent || "").replace(/\d+(\/\d+)?$/, "").trim());
const panel = (id: string) => document.getElementById(`memory-panel-${id}`) as HTMLElement;

beforeEach(() => {
  vi.clearAllMocks();
  window.history.replaceState(null, "", "/memory");
});

describe("Memory page tabs", () => {
  it("shows the five sections in the order a person meets them, with live counts", async () => {
    await renderPage();
    expect(tabNames()).toEqual(["Your notes", "Assistants", "Learned facts", "Import", "Export & erase"]);
    expect(screen.getByRole("tab", { name: /Your notes/ }).textContent).toContain("1"); // one written note
    expect(screen.getByRole("tab", { name: /Assistants/ }).textContent).toContain("0/2"); // none opted in
    expect(screen.getByRole("tab", { name: /Learned facts/ }).textContent).toContain("2");
  });

  it("opens on Your notes and says in one line what the section is for", async () => {
    await renderPage();
    expect(screen.getByRole("tab", { name: /Your notes/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByText(en.memoryPage.tab_notes_purpose)).toBeTruthy();
    expect(panel("notes").hidden).toBe(false);
    expect(panel("facts").hidden).toBe(true);
  });

  it("switches sections, keeps the URL hash in step, and does not add a history entry", async () => {
    await renderPage();
    const before = window.history.length;
    fireEvent.click(screen.getByRole("tab", { name: /Learned facts/ }));
    expect(window.location.hash).toBe("#facts");
    expect(window.history.length).toBe(before);
    expect(panel("facts").hidden).toBe(false);
    expect(panel("notes").hidden).toBe(true);
    expect(screen.getByText(en.memoryPage.tab_facts_purpose)).toBeTruthy();
    // Add-a-memory lives with the facts it adds to; imports live under Import.
    expect(within(panel("facts")).getByText("add-fact-form")).toBeTruthy();
    expect(within(panel("import")).getByText("import-memory-card")).toBeTruthy();
    expect(within(panel("import")).getByText("import-chats-card")).toBeTruthy();
  });

  it("opens the section named in the URL hash", async () => {
    window.history.replaceState(null, "", "/memory#privacy");
    await renderPage();
    expect(screen.getByRole("tab", { name: /Export & erase/ }).getAttribute("aria-selected")).toBe("true");
    expect(panel("privacy").hidden).toBe(false);
  });

  it("falls back to the first section when the hash is not available", async () => {
    window.history.replaceState(null, "", "/memory#nonsense");
    await renderPage(overview({ blocksEnabled: false, selfServiceEnabled: false }));
    // No notes, no assistants tab, no privacy tab: facts is the first one left.
    expect(tabNames()).toEqual(["Learned facts", "Import"]);
    expect(screen.getByRole("tab", { name: /Learned facts/ }).getAttribute("aria-selected")).toBe("true");
  });

  it("nudges toward Assistants when notes exist but no assistant reads them", async () => {
    await renderPage();
    expect(screen.getByText(en.memoryPage.notesUnusedTitle)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: en.memoryPage.notesUnusedAction }));
    expect(screen.getByRole("tab", { name: /Assistants/ }).getAttribute("aria-selected")).toBe("true");
    expect(window.location.hash).toBe("#assistants");
  });

  it("stays quiet once an assistant has shared memory on", async () => {
    await renderPage(overview(), [{ ...AGENTS[0], shareMemoryWithTenant: true }, AGENTS[1]]);
    expect(screen.queryByText(en.memoryPage.notesUnusedTitle)).toBeNull();
    expect(screen.getByRole("tab", { name: /Assistants/ }).textContent).toContain("1/2");
  });

  it("keeps an unsaved note draft when you visit another section and come back", async () => {
    await renderPage();
    const box = within(panel("notes")).getAllByRole("textbox")[0] as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: "half-written thought" } });
    fireEvent.click(screen.getByRole("tab", { name: /Learned facts/ }));
    fireEvent.click(screen.getByRole("tab", { name: /Your notes/ }));
    await waitFor(() => expect((within(panel("notes")).getAllByRole("textbox")[0] as HTMLTextAreaElement).value).toBe("half-written thought"));
  });

  it("gives every tab a panel that names it, for screen readers", async () => {
    await renderPage();
    for (const id of ["notes", "assistants", "facts", "import", "privacy"]) {
      expect(panel(id).getAttribute("role")).toBe("tabpanel");
      expect(panel(id).getAttribute("aria-labelledby")).toBe(`memory-tab-${id}`);
      expect(document.getElementById(`memory-tab-${id}`)?.getAttribute("aria-controls")).toBe(`memory-panel-${id}`);
    }
  });
});
