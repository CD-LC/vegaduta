// Capacity meters, their text labels and the explainer on /data-tables, rendered with the real English strings.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../../messages/en.json";

const getDataTablesConfig = vi.fn();
const listDataTables = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getDataTablesConfig() { return getDataTablesConfig; },
    get listDataTables() { return listDataTables; },
  },
  triggerBrowserDownload: vi.fn(),
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

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
    default: ({ href, children }: { href: string; children: unknown }) => React.createElement("a", { href }, children as any),
  };
});

import CapacityMeter from "./CapacityMeter";
import DataTableCapacity from "./DataTableCapacity";
import DataTableExplainer from "./DataTableExplainer";
import DataTablesPanel from "./DataTablesPanel";
import { capacity } from "./capacity";
import type { DataTablesConfig, DataTableSummary } from "../../lib/dataTables";

function config(over: Partial<DataTablesConfig> = {}): DataTablesConfig {
  return {
    enabled: true,
    planLocked: false,
    maxTables: 5,
    maxRowsPerTable: 100,
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

beforeEach(() => {
  vi.clearAllMocks();
  mockPermissions = ["MANAGE_WORKFLOWS_ADMIN"];
});

describe("CapacityMeter", () => {
  const meter = (used: number, max: number) =>
    render(<CapacityMeter value={capacity(used, max)} label="Orders: rows used" nearLabel="Near limit" fullLabel="Full" />);

  it("is a meter with real values and no flag while there is room", () => {
    const { container } = meter(10, 100);
    const el = screen.getByRole("meter", { name: "Orders: rows used" });
    expect(el.getAttribute("aria-valuenow")).toBe("10");
    expect(el.getAttribute("aria-valuemax")).toBe("100");
    expect(container.querySelector("[data-capacity-flag]")).toBeNull();
  });

  it("says 'Near limit' in text at 80% and 'Full' at the limit", () => {
    const near = meter(80, 100);
    expect(near.container.querySelector("[data-capacity-flag]")?.textContent).toBe("Near limit");
    near.unmount();
    const full = meter(100, 100);
    expect(full.container.querySelector("[data-capacity-flag]")?.textContent).toBe("Full");
    expect(screen.getByRole("meter").getAttribute("aria-valuetext")).toBe("Orders: rows used - Full");
  });

  it("draws nothing when the limit is unknown", () => {
    const { container } = meter(10, 0);
    expect(container.firstChild).toBeNull();
  });
});

describe("DataTableCapacity", () => {
  it("renders nothing with no tables (the page's empty state stands alone)", () => {
    const { container } = render(<DataTableCapacity tables={[]} config={config()} />);
    expect(container.firstChild).toBeNull();
  });

  it("says how many tables are used and that no table is near its row limit", () => {
    render(<DataTableCapacity tables={[summary(), summary({ id: "t2", name: "Leads", rowCount: 20 })]} config={config()} />);
    expect(screen.getByText("2 of 5 tables used. No table is near its limit of 100 rows.")).toBeTruthy();
    expect(screen.getByText("Tables you can still create: 3")).toBeTruthy();
    expect(screen.getByRole("meter").getAttribute("aria-valuenow")).toBe("2");
  });

  it("counts near and full tables in the sentence and lists every table in the table view", () => {
    render(
      <DataTableCapacity
        tables={[summary(), summary({ id: "t2", name: "Leads", rowCount: 85 }), summary({ id: "t3", name: "Log", rowCount: 100 })]}
        config={config()}
      />
    );
    expect(screen.getByText("3 of 5 tables used. Tables near the limit of 100 rows: 1. Full: 1.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "View as table" }));
    const rows = within(screen.getByRole("table")).getAllByRole("row");
    expect(rows.slice(1).map((row) => row.textContent)).toEqual(["Orders11001%OK", "Leads8510085%Near limit", "Log100100100%Full"]);
  });

  it("flags the table allowance itself when it is used up", () => {
    const { container } = render(<DataTableCapacity tables={[summary(), summary({ id: "t2", name: "B" })]} config={config({ maxTables: 2 })} />);
    expect(container.querySelector("[data-capacity-flag]")?.textContent).toBe("Full");
    expect(screen.getByText("Tables you can still create: 0")).toBeTruthy();
  });

  it("claims no limit it does not know", () => {
    render(<DataTableCapacity tables={[summary()]} config={config({ maxTables: 0, maxRowsPerTable: 0 })} />);
    expect(screen.getByText("Tables: 1.")).toBeTruthy();
    expect(screen.queryByRole("meter")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "View as table" }));
    expect(within(screen.getByRole("table")).getAllByRole("row")[1].textContent).toBe("Orders1---");
  });
});

describe("DataTableExplainer", () => {
  it("is a closed disclosure with the three steps and the read / write branches", () => {
    const { container } = render(<DataTableExplainer />);
    const details = container.querySelector("details")!;
    expect(details.hasAttribute("open")).toBe(false);
    expect([...container.querySelectorAll("ol > li")].map((li) => li.querySelector("p")?.textContent)).toEqual([
      "A workflow or agent uses a table",
      "The rows stay between runs",
      "View, edit and export here",
    ]);
    expect([...container.querySelectorAll("[data-flow-outcome] .font-semibold")].map((n) => n.textContent)).toEqual(["Read", "Write"]);
  });
});

describe("DataTablesPanel capacity", () => {
  it("puts a row meter with a text flag on each table of the list", async () => {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([summary(), summary({ id: "t2", name: "Leads", rowCount: 85 }), summary({ id: "t3", name: "Log", rowCount: 100 })]);
    render(<DataTablesPanel />);

    const list = await screen.findByTestId("data-table-list");
    const items = within(list).getAllByRole("listitem");
    expect(items.map((li) => li.querySelector("[data-capacity-level]")?.getAttribute("data-capacity-level"))).toEqual(["near", "full", "ok"]);
    expect(within(items[0]).getByText("Near limit")).toBeTruthy();
    expect(within(items[1]).getByText("Full")).toBeTruthy();
    expect(items[2].querySelector("[data-capacity-flag]")).toBeNull();
  });

  it("shows the read-only capacity figures and the explainer to a member who cannot edit", async () => {
    mockPermissions = [];
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([summary()]);
    render(<DataTablesPanel />);

    expect(await screen.findByText("1 of 5 tables used. No table is near its limit of 100 rows.")).toBeTruthy();
    expect(screen.getByText("How this works")).toBeTruthy();
    // The write controls stay hidden (B12).
    expect(screen.queryByText(en.dataTables.newTable)).toBeNull();
    expect(screen.queryByText(en.dataTables.importCsv)).toBeNull();
  });

  it("keeps the empty workspace clean: explainer and empty state, no capacity chart", async () => {
    getDataTablesConfig.mockResolvedValue(config());
    listDataTables.mockResolvedValue([]);
    render(<DataTablesPanel />);

    await waitFor(() => expect(screen.getByText(en.dataTables.emptyTitle)).toBeTruthy());
    expect(screen.getByText("How this works")).toBeTruthy();
    expect(screen.queryByText(en.dataTables.capacityTitle)).toBeNull();
    expect(screen.queryByRole("meter")).toBeNull();
  });
});
