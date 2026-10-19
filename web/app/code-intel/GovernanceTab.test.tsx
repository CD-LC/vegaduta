import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";

const api = vi.hoisted(() => ({
  getPolicy: vi.fn(),
  listApprovals: vi.fn(),
  audit: vi.fn(),
  setPolicy: vi.fn(),
  resolveApproval: vi.fn(),
}));
vi.mock("./lib", () => ({ codeIntel: api }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import GovernanceTab from "./GovernanceTab";

const policy = {
  mode: "read_only",
  exec_enabled: false,
  apply_enabled: false,
  max_files_per_apply: 5,
  max_bytes_per_file: 2048,
  max_command_timeout: 30,
  protected_path_globs: [] as string[],
};

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
});

describe("GovernanceTab load errors", () => {
  it("shows an error with Retry when approvals fail to load, not 'nothing waiting'", async () => {
    api.getPolicy.mockResolvedValue(policy);
    api.audit.mockResolvedValue([]);
    api.listApprovals.mockRejectedValueOnce(new Error("503 upstream")).mockResolvedValue([]);
    render(<GovernanceTab onError={vi.fn()} />);
    await waitFor(() => expect(screen.getByText(/Couldn't load pending approvals: 503 upstream/)).toBeTruthy());
    expect(screen.queryByText(en.codeIntel.nothingAwaitingApproval)).toBeNull();
    fireEvent.click(screen.getAllByRole("button", { name: new RegExp(en.codeIntel.retryButton) })[0]);
    await waitFor(() => expect(screen.getByText(en.codeIntel.nothingAwaitingApproval)).toBeTruthy());
    expect(screen.queryByText(/Couldn't load pending approvals/)).toBeNull();
  });

  it("shows an error with Retry when the audit trail fails to load", async () => {
    api.getPolicy.mockResolvedValue(policy);
    api.listApprovals.mockResolvedValue([]);
    api.audit.mockRejectedValue(new Error("boom"));
    render(<GovernanceTab onError={vi.fn()} />);
    await waitFor(() => expect(screen.getByText(/Couldn't load the audit trail: boom/)).toBeTruthy());
    expect(screen.queryByText(en.codeIntel.noActivityYet)).toBeNull();
  });

  it("labels the audit cap when the newest-60 window is full", async () => {
    api.getPolicy.mockResolvedValue(policy);
    api.listApprovals.mockResolvedValue([]);
    api.audit.mockResolvedValue(Array.from({ length: 60 }, (_, i) => ({ kind: "executed", command: `c${i}` })));
    render(<GovernanceTab onError={vi.fn()} />);
    await waitFor(() => expect(screen.getByText(/Showing only the newest 60 events/)).toBeTruthy());
    expect(api.audit).toHaveBeenCalledWith(60);
  });
});
