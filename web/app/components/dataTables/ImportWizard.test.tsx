// The CSV import as a person uses it: drop or choose a file, review the guessed columns, fix a name, see the preview and
// the footer summary, and only then create. t() returns the key (with values appended) so assertions bind to keys.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${Object.values(values).join(",")}` : key,
}));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const createTableWithRows = vi.fn();
vi.mock("./createTable", () => ({ createTableWithRows: (...a: unknown[]) => createTableWithRows(...a) }));

import ImportWizard from "./ImportWizard";
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

const CSV = "id,firstname,lastname\n100,Violet,Ng\n101,Arlina,Roy\n102,Aurore,Dev\n";

function file(text = CSV, name = "myFile0.csv") {
  const f = new File([text], name, { type: "text/csv" });
  // jsdom's File has no text(); every browser does, and the wizard relies on it.
  Object.defineProperty(f, "text", { value: () => Promise.resolve(text) });
  return f;
}

async function choose(f: File) {
  fireEvent.change(screen.getByLabelText("chooseFile"), { target: { files: [f] } });
  await screen.findAllByTestId("data-table-wizard-column");
}

beforeEach(() => {
  vi.clearAllMocks();
  createTableWithRows.mockResolvedValue({ table: { id: "t1", name: "myFile0" }, inserted: 3, incomplete: false });
});

describe("ImportWizard", () => {
  it("starts as a drop zone with the three steps and a disabled Create", () => {
    render(<ImportWizard config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByText("impDropTitle")).toBeTruthy();
    const steps = within(screen.getByRole("list", { name: "impStepsLabel" })).getAllByRole("listitem");
    expect(steps.map((s) => s.textContent?.replace(/^\d+/, ""))).toEqual(["impStepFile", "impStepColumns", "impStepImport"]);
    expect(steps[0].getAttribute("aria-current")).toBe("step");
    expect((screen.getByRole("button", { name: /impCreate/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole("status").textContent).toBe("impFooterIdle");
  });

  it("after choosing a file shows what was found, one compact line per column, a preview and the footer summary", async () => {
    render(<ImportWizard config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    await choose(file());
    expect(screen.getByText("myFile0.csv")).toBeTruthy();
    expect(screen.getByText("impDetected:3,3")).toBeTruthy();
    expect(screen.getByText("impChangeFile")).toBeTruthy();

    const cols = screen.getAllByTestId("data-table-wizard-column");
    expect(cols).toHaveLength(3);
    expect((within(cols[0]).getByLabelText("columnNameLabel") as HTMLInputElement).value).toBe("id");
    expect(within(cols[1]).getByText("Violet | Arlina | Aurore")).toBeTruthy();

    // The table name defaults to the file name, the second step is now current, and the footer says what will happen.
    expect((screen.getByLabelText("nameLabel") as HTMLInputElement).value).toBe("myFile0");
    const steps = within(screen.getByRole("list", { name: "impStepsLabel" })).getAllByRole("listitem");
    expect(steps[1].getAttribute("aria-current")).toBe("step");
    expect(screen.getByRole("status").textContent).toBe("impSummary:3,0");
    expect(screen.getByText("impPreviewHeading:3")).toBeTruthy();
    expect((screen.getByRole("button", { name: /impCreate/ }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("blocks Create and flags both fields when two columns end up with the same name", async () => {
    render(<ImportWizard config={CONFIG} onCreated={vi.fn()} onCancel={vi.fn()} />);
    await choose(file());
    const cols = screen.getAllByTestId("data-table-wizard-column");
    fireEvent.change(within(cols[1]).getByLabelText("columnNameLabel"), { target: { value: "ID" } });
    expect(screen.getByText("impNameClash:ID")).toBeTruthy();
    expect(within(cols[1]).getByLabelText("columnNameLabel").getAttribute("aria-invalid")).toBe("true");
    expect((screen.getByRole("button", { name: /impCreate/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("creates the table from the reviewed columns and reports it through onCreated", async () => {
    const onCreated = vi.fn();
    render(<ImportWizard config={CONFIG} onCreated={onCreated} onCancel={vi.fn()} />);
    await choose(file());
    fireEvent.click(screen.getByRole("button", { name: /impCreate/ }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: "t1", name: "myFile0" }));
    const arg = createTableWithRows.mock.calls[0][0];
    expect(arg.name).toBe("myFile0");
    expect(arg.columns.map((c: { name: string }) => c.name)).toEqual(["id", "firstname", "lastname"]);
    expect(arg.rows).toHaveLength(3);
  });

  it("explains a file that is too big and keeps Create off", () => {
    render(<ImportWizard config={{ ...CONFIG, maxImportBytes: 10 }} onCreated={vi.fn()} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("chooseFile"), { target: { files: [file()] } });
    expect(screen.getByRole("alert").textContent).toContain("impTooBig");
    expect((screen.getByRole("button", { name: /impCreate/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("closes from the header X and from Cancel", () => {
    const onCancel = vi.fn();
    render(<ImportWizard config={CONFIG} onCreated={vi.fn()} onCancel={onCancel} />);
    fireEvent.click(screen.getByRole("button", { name: "impClose" }));
    fireEvent.click(screen.getByRole("button", { name: "cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });
});
