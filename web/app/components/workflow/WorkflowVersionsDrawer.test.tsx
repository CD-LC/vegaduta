import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
    const raw = ((en as any)[ns] ?? {})[key];
    if (typeof raw !== "string") return `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (m, k) => (values && k in values ? String(values[k]) : m));
  },
}));

const fetchVersions = vi.fn();
vi.mock("./versionDiff", async () => {
  const actual = await vi.importActual<any>("./versionDiff");
  return { ...actual, fetchWorkflowVersions: (...a: any[]) => fetchVersions(...a) };
});
vi.mock("./VersionDiffPanel", () => ({
  default: ({ currentVersion, onClose }: any) => (
    <div data-testid="diff">
      diff v{currentVersion}
      <button onClick={onClose}>close diff</button>
    </div>
  ),
}));
const save = vi.fn();
const promote = vi.fn();
const rollback = vi.fn();
vi.mock("../../lib/api/workflowVersions", () => ({
  saveWorkflowVersion: (...a: any[]) => save(...a),
  restoreWorkflowVersion: vi.fn(),
  promoteWorkflowVersion: (...a: any[]) => promote(...a),
  rollbackWorkflowVersion: (...a: any[]) => rollback(...a),
}));

import WorkflowVersionsDrawer from "./WorkflowVersionsDrawer";

const rows = Array.from({ length: 12 }, (_, i) => ({
  id: `id-${i + 1}`,
  versionNumber: 12 - i,
  workflowName: "wf",
  status: "DRAFT",
  changeSummary: `note ${12 - i}`,
  createdBy: null as string | null,
  createdAt: "2026-10-01T10:00:00Z",
}));

describe("WorkflowVersionsDrawer", () => {
  beforeEach(() => {
    fetchVersions.mockReset();
    save.mockReset();
    promote.mockReset();
    rollback.mockReset();
  });

  it("pages the version list, filters it and opens the diff panel", async () => {
    fetchVersions.mockResolvedValue({ ok: true, data: rows });
    render(<WorkflowVersionsDrawer workflowId="w1" onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("Version 12");
    expect(screen.getAllByText(/^Version \d+$/)).toHaveLength(10);
    fireEvent.change(screen.getByRole("searchbox", { name: "Search versions" }), { target: { value: "note 3" } });
    expect(screen.getAllByText(/^Version \d+$/)).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: /Compare/ }));
    expect(screen.getByTestId("diff").textContent).toContain("diff v3");
  });

  it("saves a version with the change note", async () => {
    fetchVersions.mockResolvedValue({ ok: true, data: rows });
    save.mockResolvedValue({ createdNew: true, version: { versionNumber: 13 } });
    render(<WorkflowVersionsDrawer workflowId="w1" onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("Version 12");
    fireEvent.change(screen.getByLabelText("Change note (optional)"), { target: { value: "ship it" } });
    fireEvent.click(screen.getByRole("button", { name: "Save version" }));
    await waitFor(() => expect(save).toHaveBeenCalledWith("w1", "ship it"));
  });

  it("promotes after confirming", async () => {
    fetchVersions.mockResolvedValue({ ok: true, data: rows.slice(0, 2) });
    promote.mockResolvedValue({});
    render(<WorkflowVersionsDrawer workflowId="w1" onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("Version 12");
    fireEvent.click(screen.getAllByRole("button", { name: /Promote/ })[0]);
    fireEvent.click(await screen.findByRole("button", { name: "Promote" }));
    await waitFor(() => expect(promote).toHaveBeenCalledWith("w1", 12, "PRODUCTION"));
  });

  it("shows an error with Retry, and hides write actions when read-only", async () => {
    fetchVersions.mockResolvedValueOnce({ ok: false, unsupported: false, message: "boom" });
    render(<WorkflowVersionsDrawer workflowId="w1" canWrite={false} onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("boom");
    fetchVersions.mockResolvedValueOnce({ ok: true, data: rows.slice(0, 2) });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByText("Version 12");
    expect(screen.queryByRole("button", { name: /Restore/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Save version" })).toBeNull();
  });

  it("shows the release label on a version row", async () => {
    fetchVersions.mockResolvedValue({ ok: true, data: [{ ...rows[0], label: "PRODUCTION" }, { ...rows[1], label: "STAGING" }] });
    render(<WorkflowVersionsDrawer workflowId="w1" onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("Version 12");
    expect(screen.getByText("Production")).toBeTruthy();
    expect(screen.getByText("Staging")).toBeTruthy();
  });

  it("hides write actions for a non-editor accessLevel", async () => {
    fetchVersions.mockResolvedValue({ ok: true, data: rows.slice(0, 2) });
    render(<WorkflowVersionsDrawer workflowId="w1" accessLevel="VIEWER" onFocusNode={() => {}} onClose={() => {}} />);
    await screen.findByText("Version 12");
    expect(screen.queryByRole("button", { name: /Restore/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /Promote/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "Save version" })).toBeNull();
    expect(screen.getAllByRole("button", { name: /Compare/ }).length).toBeGreaterThan(0);
  });
});
