// Starter templates as a person uses them: pick a card, name the table, decide on sample rows, create. t() returns the
// key (with values appended) so assertions bind to keys, not English copy.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(",")}` : key,
}));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const createTableWithRows = vi.fn();
vi.mock("./createTable", () => ({ createTableWithRows: (...a: unknown[]) => createTableWithRows(...a) }));

import TemplatesGallery from "./TemplatesGallery";
import { TABLE_TEMPLATES } from "../../lib/dataTableTemplates";
import type { DataTablesConfig } from "../../lib/dataTables";

const CONFIG: DataTablesConfig = {
  enabled: true,
  planLocked: false,
  maxTables: 50,
  maxRowsPerTable: 100000,
  maxColumns: 50,
  maxCellBytes: 65536,
  maxBulkInsert: 1000,
  maxImportBytes: 5242880,
  maxImportRows: 10000,
};

const CRM = TABLE_TEMPLATES.find((x) => x.id === "crm")!;

beforeEach(() => {
  vi.clearAllMocks();
  createTableWithRows.mockResolvedValue({ table: { id: "t1", name: "Leads" }, inserted: 3, incomplete: false });
});

describe("TemplatesGallery", () => {
  it("lists every template as a card with its title, description and first columns, and nothing picked yet", () => {
    render(<TemplatesGallery config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    const cards = screen.getAllByRole("button", { pressed: false }).filter((b) => b.hasAttribute("aria-pressed"));
    expect(cards).toHaveLength(TABLE_TEMPLATES.length);
    const crm = cards.find((c) => within(c).queryByText("tpl_crm_title"))!;
    expect(within(crm).getByText("tpl_crm_desc")).toBeTruthy();
    expect(within(crm).getByText("name")).toBeTruthy();
    expect(within(crm).getByText(`tplMoreColumns:${CRM.columns.length - 4}`)).toBeTruthy();
    expect(screen.getByRole("status").textContent).toBe("tplFooterIdle");
    expect((screen.getByRole("button", { name: /tplCreate/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByLabelText("nameLabel")).toBeNull();
  });

  it("picking a card marks it, fills the name, lists all columns with types and says what will be created", () => {
    render(<TemplatesGallery config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByText("tpl_crm_title"));
    expect(screen.getByText("tpl_crm_title").closest("button")!.getAttribute("aria-pressed")).toBe("true");
    expect((screen.getByLabelText("nameLabel") as HTMLInputElement).value).toBe(CRM.name);
    expect(screen.getByText(`tplColumnsHeading:${CRM.columns.length}`)).toBeTruthy();
    expect(screen.getByText("deal_value")).toBeTruthy();
    const samples = CRM.samples("2026-10-08").length;
    expect(screen.getByRole("status").textContent).toBe(`tplFooterReady:${CRM.name},${CRM.columns.length},${samples}`);
    const steps = within(screen.getByRole("list", { name: "tplStepsLabel" })).getAllByRole("listitem");
    expect(steps[1].getAttribute("aria-current")).toBe("step");
  });

  it("creates with the template's columns, its description and the sample rows", async () => {
    const onCreated = vi.fn();
    render(<TemplatesGallery config={CONFIG} onCreated={onCreated} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByText("tpl_crm_title"));
    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "  My leads " } });
    fireEvent.click(screen.getByRole("button", { name: /tplCreate/ }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: "t1", name: "Leads" }));
    const arg = createTableWithRows.mock.calls[0][0];
    expect(arg.name).toBe("My leads");
    expect(arg.description).toBe("tpl_crm_desc");
    expect(arg.columns).toEqual(CRM.columns);
    expect(arg.rows.length).toBe(CRM.samples("2026-10-08").length);
  });

  it("leaves the sample rows out when that is switched off, and says so in the footer", async () => {
    render(<TemplatesGallery config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByText("tpl_crm_title"));
    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("status").textContent).toBe(`tplFooterReady:${CRM.name},${CRM.columns.length},0`);
    fireEvent.click(screen.getByRole("button", { name: /tplCreate/ }));
    await waitFor(() => expect(createTableWithRows).toHaveBeenCalled());
    expect(createTableWithRows.mock.calls[0][0].rows).toEqual([]);
  });

  it("will not create with an empty name, and closes from the X and from Cancel", () => {
    const onCancel = vi.fn();
    render(<TemplatesGallery config={CONFIG} onCreated={vi.fn()} onCancel={onCancel} />);
    fireEvent.click(screen.getByText("tpl_crm_title"));
    fireEvent.change(screen.getByLabelText("nameLabel"), { target: { value: "   " } });
    expect((screen.getByRole("button", { name: /tplCreate/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "tplClose" }));
    fireEvent.click(screen.getByRole("button", { name: "cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });
});
