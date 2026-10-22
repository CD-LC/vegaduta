// The Data Tables console: which state the page shows for what the server says (flag off, plan-locked, a core that
// predates the feature, usable) and the two interactions that matter most - inline cell edit and create table.
// t() returns the key so assertions bind to keys, not to English copy.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getDataTablesConfig = vi.fn();
const listDataTables = vi.fn();
const listDataTableRows = vi.fn();
const updateDataTableRow = vi.fn();
const createDataTable = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getDataTablesConfig() {
      return getDataTablesConfig;
    },
    get listDataTables() {
      return listDataTables;
    },
    get listDataTableRows() {
      return listDataTableRows;
    },
    get updateDataTableRow() {
      return updateDataTableRow;
    },
    get createDataTable() {
      return createDataTable;
    },
  },
  triggerBrowserDownload: vi.fn(),
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

// B12: writes are gated server-side to MANAGE_WORKFLOWS_ADMIN; the panel mirrors it. Admin by default so every
// existing test keeps its meaning; the "plain member" block flips it.
let mockPermissions: string[] = ["MANAGE_WORKFLOWS_ADMIN"];
vi.mock("../AuthProvider", () => ({
  useAuth: () => ({ hasPermission: (name: string) => mockPermissions.includes(name) }),
}));

vi.mock("../../lib/toastBus", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

vi.mock("next/link", async () => {
  const React = await import("react");
  return {
    default: ({ href, children }: { href: string; children: unknown }) =>
      React.createElement("a", { href }, children as any),
  };
});

import DataTablesPanel from "./DataTablesPanel";
import type { DataTablesConfig, DataTableSummary } from "../../lib/dataTables";

function config(over: Partial<DataTablesConfig> = {}): DataTablesConfig {
  return {
    enabled: true,
    planLocked: false,
    maxTables: 50,
    maxRowsPerTable: 100000,
    maxColumns: 50,
    maxCellBytes: 65536,
    maxBulkInsert: 1000,
    maxImportBytes: 5242880,
    maxImportRows: 10000,
    ...over,
  };
}

function summary(over: Partial<DataTableSummary> = {}): DataTableSummary {
  return {
    id: "t1",
    name: "Orders",
    description: null,
    columns: [{ name: "email", type: "text" }],
    rowCount: 1,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...over,
  };
}

function rowsPage(data: Record<string, unknown>) {
  return { items: [{ id: "r1", data, createdAt: "", updatedAt: "" }], total: 1, limit: 50, offset: 0 };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockPermissions = ["MANAGE_WORKFLOWS_ADMIN"];
});

describe("DataTablesPanel states", () => {
  it("says so when the feature is not enabled and never lists tables", async () => {
    getDataTablesConfig.mockResolvedValue(config({ enabled: false }));

    render(<DataTablesPanel />);

    expect(await screen.findByText("disabledTitle")).toBeInTheDocument();
    expect(listDataTables).not.toHaveBeenCalled();
  });

  it("treats a core without the endpoint (404) as not enabled, not as a failure", async () => {
    getDataTablesConfig.mockRejectedValue(Object.assign(new Error("gone"), { status: 404 }));

    render(<DataTablesPanel />);

    expect(await screen.findByText("disabledTitle")).toBeInTheDocument();
    expect(screen.queryByText("loadFailed")).not.toBeInTheDocument();
  });

  it("shows an inline upgrade card when the plan is locked", async () => {
    getDataTablesConfig.mockResolvedValue(config({ planLocked: true }));

    render(<DataTablesPanel />);

    expect(await screen.findByText("planLockedTitle")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "upgrade" })).toHaveAttribute("href", "/admin/billing");
    expect(listDataTables).not.toHaveBeenCalled();
  });

  it("shows the plan-locked card without an upgrade link on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    try {
      getDataTablesConfig.mockResolvedValue(config({ planLocked: true }));
      render(<DataTablesPanel />);
      expect(await screen.findByText("planLockedTitle")).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "upgrade" })).not.toBeInTheDocument();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it("reports a real failure with a retry", async () => {
    getDataTablesConfig.mockRejectedValue(Object.assign(new Error("boom"), { status: 500 }));

    render(<DataTablesPanel />);

    expect(await screen.findByText("loadFailed")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "retry" })).toBeInTheDocument();
  });

  it("offers the empty state when enabled with no tables", async () => {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([]);

    render(<DataTablesPanel />);

    expect(await screen.findByText("emptyTitle")).toBeInTheDocument();
  });

  it("tells the host page when it has finished loading", async () => {
    getDataTablesConfig.mockResolvedValue(config({ enabled: false }));
    const onLoaded = vi.fn();

    render(<DataTablesPanel onLoaded={onLoaded} />);

    await waitFor(() => expect(onLoaded).toHaveBeenCalledTimes(1));
  });
});

describe("DataTablesPanel grid", () => {
  async function openOrders(data: Record<string, unknown>, table: DataTableSummary = summary()) {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([table]);
    listDataTableRows.mockResolvedValue(rowsPage(data));
    render(<DataTablesPanel />);
    fireEvent.click(await screen.findByRole("button", { name: /Orders/ }));
    await screen.findByTestId("data-table-grid");
  }

  it("lists the tables and shows the selected table's rows", async () => {
    await openOrders({ email: "a@b.co" });

    expect(await screen.findByText("a@b.co")).toBeInTheDocument();
    expect(listDataTableRows).toHaveBeenCalledWith("t1", "limit=50");
  });

  it("saves an edited cell with the typed value on Enter", async () => {
    updateDataTableRow.mockResolvedValue({ id: "r1", data: { email: "new@b.co" }, createdAt: "", updatedAt: "" });
    await openOrders({ email: "a@b.co" });

    fireEvent.click(await screen.findByRole("button", { name: "editCell" }));
    const input = screen.getByRole("textbox", { name: "editCell" });
    fireEvent.change(input, { target: { value: "new@b.co" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(updateDataTableRow).toHaveBeenCalledWith("t1", "r1", { email: "new@b.co" }));
    expect(await screen.findByText("new@b.co")).toBeInTheDocument();
  });

  it("does not call the server when the edit changes nothing", async () => {
    await openOrders({ email: "a@b.co" });

    fireEvent.click(await screen.findByRole("button", { name: "editCell" }));
    fireEvent.keyDown(screen.getByRole("textbox", { name: "editCell" }), { key: "Enter" });

    await waitFor(() => expect(screen.queryByRole("textbox", { name: "editCell" })).not.toBeInTheDocument());
    expect(updateDataTableRow).not.toHaveBeenCalled();
  });

  it("refuses a value that does not fit the column type before any request", async () => {
    await openOrders({ qty: 3 }, summary({ columns: [{ name: "qty", type: "number" }] }));

    fireEvent.click(await screen.findByRole("button", { name: "editCell" }));
    const input = screen.getByRole("textbox", { name: "editCell" });
    fireEvent.change(input, { target: { value: "lots" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(await screen.findByRole("alert")).toHaveTextContent("cellError_number");
    expect(updateDataTableRow).not.toHaveBeenCalled();
  });

  it("cancels an edit on Escape", async () => {
    await openOrders({ email: "a@b.co" });

    fireEvent.click(await screen.findByRole("button", { name: "editCell" }));
    const input = screen.getByRole("textbox", { name: "editCell" });
    fireEvent.change(input, { target: { value: "zzz" } });
    fireEvent.keyDown(input, { key: "Escape" });

    expect(updateDataTableRow).not.toHaveBeenCalled();
    expect(await screen.findByText("a@b.co")).toBeInTheDocument();
  });
});

describe("DataTablesPanel create", () => {
  it("creates an empty table from the name field and selects it", async () => {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([]);
    listDataTableRows.mockResolvedValue({ items: [], total: 0, limit: 50, offset: 0 });
    createDataTable.mockResolvedValue(summary({ id: "t9", name: "Leads", columns: [], rowCount: 0 }));

    render(<DataTablesPanel />);
    fireEvent.click(await screen.findByRole("button", { name: /newTable/ }));
    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "  Leads " } });
    fireEvent.click(screen.getByRole("button", { name: /^create$/ }));

    await waitFor(() =>
      expect(createDataTable).toHaveBeenCalledWith({ name: "Leads", description: undefined, columns: [] })
    );
    expect(await screen.findByTestId("data-table-grid")).toBeInTheDocument();
  });
});

describe("DataTablesPanel for a plain member (no MANAGE_WORKFLOWS_ADMIN)", () => {
  async function openAsMember() {
    mockPermissions = [];
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([summary()]);
    listDataTableRows.mockResolvedValue(rowsPage({ email: "a@b.co" }));
    render(<DataTablesPanel />);
    fireEvent.click(await screen.findByRole("button", { name: /Orders/ }));
    await screen.findByTestId("data-table-grid");
  }

  it("does not offer New table or Import CSV, which can only answer 403", async () => {
    await openAsMember();

    expect(screen.queryByRole("button", { name: /newTable/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /importCsv/ })).not.toBeInTheDocument();
  });

  it("still lets the member read the table, with no edit, add-row or delete controls", async () => {
    await openAsMember();

    expect(await screen.findByText("a@b.co")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "editCell" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /addRow/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "deleteTable" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "rename" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /exportCsv/ })).toBeInTheDocument();
  });

  it("shows the controls to a holder of the permission", async () => {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([]);
    render(<DataTablesPanel />);
    expect(await screen.findByRole("button", { name: /newTable/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /importCsv/ })).toBeInTheDocument();
  });
});
