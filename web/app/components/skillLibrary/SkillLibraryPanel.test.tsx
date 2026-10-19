import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>): string => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, name) => (values && name in values ? String(values[name]) : m));
  },
}));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() } }));
vi.mock("../../lib/api/browserDownload", () => ({ triggerBrowserDownload: vi.fn() }));

const api = vi.hoisted(() => ({
  listLibrarySkills: vi.fn(),
  getLibrarySkill: vi.fn(),
  importLibrarySkill: vi.fn(),
  exportLibrarySkill: vi.fn(),
  updateLibrarySkill: vi.fn(),
  approveLibrarySkill: vi.fn(),
  rejectLibrarySkill: vi.fn(),
  archiveLibrarySkill: vi.fn(),
  setLibrarySkillAgents: vi.fn(),
  deleteLibrarySkill: vi.fn(),
}));
vi.mock("../../lib/api/skillLibrary", () => ({ skillLibraryApi: api }));

import SkillLibraryPanel from "./SkillLibraryPanel";

const row = (over: Record<string, unknown>) => ({
  id: "s1",
  name: "Invoice triage",
  description: "Sort invoices",
  status: "ACTIVE",
  origin: "IMPORTED",
  version: 1,
  uses: 4,
  agentIds: [] as string[],
  updatedAt: "2026-01-02T00:00:00Z",
  ...over,
});

let store: Record<string, ReturnType<typeof row>[]>;

beforeEach(() => {
  vi.clearAllMocks();
  store = {
    ACTIVE: [row({})],
    PENDING: [row({ id: "p1", name: "Learned retry", status: "PENDING", origin: "LEARNED" })],
    ARCHIVED: [],
    REJECTED: [],
  };
  api.listLibrarySkills.mockImplementation(async (status: string) => ({ items: store[status] ?? [], total: (store[status] ?? []).length }));
  api.getLibrarySkill.mockImplementation(async (id: string) => {
    const r = Object.values(store).flat().find((s) => s.id === id)!;
    return { ...r, body: "# Steps\n\n- one\n- two <script>alert(1)</script>\n\n![x](https://evil.test/a.png)", files: [{ path: "ref.md", size: 2048 }] };
  });
  api.approveLibrarySkill.mockResolvedValue({});
  api.deleteLibrarySkill.mockResolvedValue(undefined);
  api.setLibrarySkillAgents.mockResolvedValue({});
  api.updateLibrarySkill.mockResolvedValue({});
});

const AGENTS = [
  { id: "a1", name: "Billing bot" },
  { id: "a2", name: "Support bot" },
];

describe("SkillLibraryPanel", () => {
  it("lists active skills and shows the pending count badge", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    expect(await screen.findByText("Invoice triage")).toBeInTheDocument();
    expect(screen.getByTestId("pending-badge")).toHaveTextContent("1");
    expect(api.listLibrarySkills).toHaveBeenCalledWith("PENDING");
  });

  it("switches to Pending review and explains an empty queue", async () => {
    store.PENDING = [];
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    fireEvent.click(screen.getByRole("tab", { name: /Pending review/ }));
    expect(await screen.findByText(en.skillLibrary.emptyPendingTitle)).toBeInTheDocument();
    expect(screen.getByText(en.skillLibrary.emptyPendingHint)).toBeInTheDocument();
  });

  it("does not leak rows from a server that ignored the status filter", async () => {
    api.listLibrarySkills.mockResolvedValue({ items: [row({}), row({ id: "p1", name: "Learned retry", status: "PENDING" })], total: 2 });
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    expect(screen.getAllByText("Invoice triage")).toHaveLength(1);
    expect(screen.queryByText("Learned retry")).not.toBeInTheDocument();
  });

  it("renders the body as safe markdown: no script element, no image fetch", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(await screen.findByText("Invoice triage"));
    const preview = await screen.findByTestId("skill-preview");
    expect(preview.querySelector("script")).toBeNull();
    expect(preview.querySelector("img")).toBeNull();
    expect(preview.textContent).toContain("<script>alert(1)</script>");
    expect(preview.textContent).toContain("[image: x]");
    expect(screen.getByText("ref.md")).toBeInTheDocument();
  });

  it("approves a pending learned skill after showing the review warning", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    fireEvent.click(screen.getByRole("tab", { name: /Pending review/ }));
    fireEvent.click(await screen.findByText("Learned retry"));
    expect(await screen.findByRole("note")).toHaveTextContent(/wrote this skill/);
    fireEvent.click(screen.getByRole("button", { name: /Approve/ }));
    await waitFor(() => expect(api.approveLibrarySkill).toHaveBeenCalledWith("p1"));
  });

  it("shows a saved-vs-draft diff for edits of a learned skill", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(screen.getByRole("tab", { name: /Pending review/ }));
    fireEvent.click(await screen.findByText("Learned retry"));
    fireEvent.click(await screen.findByRole("tab", { name: "Edit" }));
    const body = await screen.findByLabelText(en.skillLibrary.fieldBody);
    fireEvent.change(body, { target: { value: "# Steps\n\n- ONE\n- two" } });
    fireEvent.click(screen.getByRole("tab", { name: "Changes" }));
    const diff = await screen.findByTestId("skill-diff");
    expect(within(diff).getAllByRole("row").some((r) => r.getAttribute("data-diff-kind") === "change")).toBe(true);
  });

  it("has no Changes tab for a skill the user wrote or imported", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(await screen.findByText("Invoice triage"));
    await screen.findByTestId("skill-preview");
    expect(screen.queryByRole("tab", { name: "Changes" })).not.toBeInTheDocument();
  });

  it("saves per-agent enablement", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(await screen.findByText("Invoice triage"));
    fireEvent.click(await screen.findByRole("checkbox", { name: "Enable for Support bot" }));
    fireEvent.click(screen.getByRole("button", { name: en.skillLibrary.agentsSave }));
    await waitFor(() => expect(api.setLibrarySkillAgents).toHaveBeenCalledWith("s1", ["a2"]));
  });

  it("keeps an unsaved draft when another action reloads the skill", async () => {
    api.exportLibrarySkill.mockResolvedValue(new Blob(["z"]));
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(await screen.findByText("Invoice triage"));
    fireEvent.click(await screen.findByRole("tab", { name: "Edit" }));
    const body = (await screen.findByLabelText(en.skillLibrary.fieldBody)) as HTMLTextAreaElement;
    fireEvent.change(body, { target: { value: "my unsaved edit" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Enable for Support bot" }));
    fireEvent.click(screen.getByRole("button", { name: en.skillLibrary.agentsSave }));
    await waitFor(() => expect(api.setLibrarySkillAgents).toHaveBeenCalled());
    await waitFor(() => expect(api.getLibrarySkill.mock.calls.length).toBeGreaterThan(1));
    expect((screen.getByLabelText(en.skillLibrary.fieldBody) as HTMLTextAreaElement).value).toBe("my unsaved edit");
    fireEvent.click(screen.getByRole("button", { name: /Export/ }));
    await waitFor(() => expect(api.exportLibrarySkill).toHaveBeenCalledWith("s1"));
    expect((screen.getByLabelText(en.skillLibrary.fieldBody) as HTMLTextAreaElement).value).toBe("my unsaved edit");
  });

  it("refuses to approve a skill that changed after it was opened", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    fireEvent.click(screen.getByRole("tab", { name: /Pending review/ }));
    fireEvent.click(await screen.findByText("Learned retry"));
    await screen.findByRole("note");
    const original = api.getLibrarySkill.getMockImplementation()!;
    api.getLibrarySkill.mockImplementation(async (id: string) => ({ ...(await original(id)), body: "rewritten after open", version: 2 }));
    fireEvent.click(screen.getByRole("button", { name: /Approve/ }));
    await waitFor(() => expect(api.getLibrarySkill.mock.calls.length).toBeGreaterThan(2));
    expect(api.approveLibrarySkill).not.toHaveBeenCalled();
  });

  it("asks for confirmation before deleting", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    fireEvent.click(await screen.findByText("Invoice triage"));
    fireEvent.click(await screen.findByRole("button", { name: /^Delete$/ }));
    expect(api.deleteLibrarySkill).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(api.deleteLibrarySkill).toHaveBeenCalledWith("s1"));
  });

  it("rejects a wrong file type in the dropzone without calling the API", async () => {
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    const input = screen.getByTestId("skill-import-input");
    fireEvent.change(input, { target: { files: [new File(["x"], "evil.exe")] } });
    expect(await screen.findByRole("alert")).toHaveTextContent(en.skillLibrary.importBadType);
    expect(api.importLibrarySkill).not.toHaveBeenCalled();
  });

  it("imports a zip and opens the new skill", async () => {
    api.importLibrarySkill.mockResolvedValue(row({ id: "s9", name: "Fresh", status: "PENDING" }));
    store.PENDING.push(row({ id: "s9", name: "Fresh", status: "PENDING" }));
    render(<SkillLibraryPanel agents={AGENTS} />);
    await screen.findByText("Invoice triage");
    fireEvent.change(screen.getByTestId("skill-import-input"), { target: { files: [new File(["zip"], "pack.zip")] } });
    await waitFor(() => expect(api.importLibrarySkill).toHaveBeenCalled());
    await waitFor(() => expect(api.getLibrarySkill).toHaveBeenCalledWith("s9"));
  });

  it("tells the user when the list cannot be loaded", async () => {
    api.listLibrarySkills.mockRejectedValue(new Error("boom"));
    render(<SkillLibraryPanel agents={AGENTS} />);
    expect(await screen.findByTestId("skill-library-failed")).toBeInTheDocument();
  });
});
