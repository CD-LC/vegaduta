// The picker's contract with core: hidden when the server says templates are
// off, the stored shape when one is picked, and plain text again when cleared.

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const listWhatsAppTemplates = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get listWhatsAppTemplates() { return listWhatsAppTemplates; },
  },
}));

// t() returns the key, so assertions bind to keys rather than English copy.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock("next/link", () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>,
}));

// The console-wide Select is an animated popover; a native select drives the same onChange contract.
vi.mock("../Select", () => ({
  default: ({ value, onChange, options, ...rest }: any) => (
    <select value={value} onChange={onChange} data-testid={rest["data-testid"]}>
      {options.map((o: { value: string; label: string }) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

// The real field needs the expression scope, schema fetches and a mirror layer; a plain input is the contract here.
vi.mock("./ExpressionField", () => ({
  ScopedExpressionField: ({ field, value, onChange }: { field: string; value: string; onChange: (v: string) => void }) => (
    <input data-testid={`expr-${field}`} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import WhatsAppTemplateFields from "./WhatsAppTemplateFields";
import { templateKey } from "./whatsappTemplates";

const reminder = { name: "invoice_reminder", language: "en_US", bodyParamCount: 2, preview: "Hi {{1}}, invoice {{2}} is due.", category: "UTILITY" };

function httpError(status: number, message = "nope") {
  return Object.assign(new Error(message), { status });
}

beforeEach(() => {
  listWhatsAppTemplates.mockReset();
});

describe("WhatsAppTemplateFields", () => {
  it("always offers the message field and stores it as whatsappMessage", async () => {
    listWhatsAppTemplates.mockRejectedValue(httpError(404));
    const setConfig = vi.fn();
    render(<WhatsAppTemplateFields config={{}} setConfig={setConfig} />);
    fireEvent.change(screen.getByTestId("expr-whatsappMessage"), { target: { value: "Your invoice is due" } });
    expect(setConfig).toHaveBeenCalledWith({ whatsappMessage: "Your invoice is due" });
    await waitFor(() => expect(screen.queryByTestId("whatsapp-templates-loading")).toBeNull());
  });

  it("renders no template UI when the server has templates switched off (404)", async () => {
    listWhatsAppTemplates.mockRejectedValue(httpError(404, "WhatsApp templates are not enabled."));
    render(<WhatsAppTemplateFields config={{ whatsappTemplate: { name: "a", language: "en" } }} setConfig={vi.fn()} />);
    await waitFor(() => expect(screen.queryByTestId("whatsapp-templates-loading")).toBeNull());
    for (const id of ["whatsapp-template-toggle", "whatsapp-template-select", "whatsapp-templates-empty", "whatsapp-templates-error", "whatsapp-templates-forbidden"]) {
      expect(screen.queryByTestId(id)).toBeNull();
    }
  });

  it("explains a missing permission (403) and a Meta refusal (400)", async () => {
    listWhatsAppTemplates.mockRejectedValue(httpError(403));
    const first = render(<WhatsAppTemplateFields config={{}} setConfig={vi.fn()} />);
    await screen.findByTestId("whatsapp-templates-forbidden");
    first.unmount();

    listWhatsAppTemplates.mockRejectedValue(httpError(400, "Meta would not list this account's message templates (190)"));
    render(<WhatsAppTemplateFields config={{}} setConfig={vi.fn()} />);
    const error = await screen.findByTestId("whatsapp-templates-error");
    expect(error.textContent).toContain("Meta would not list this account's message templates (190)");
  });

  it("points at Channels when there are no approved templates", async () => {
    listWhatsAppTemplates.mockResolvedValue([]);
    render(<WhatsAppTemplateFields config={{}} setConfig={vi.fn()} />);
    const empty = await screen.findByTestId("whatsapp-templates-empty");
    expect(empty.querySelector("a")?.getAttribute("href")).toBe("/admin/channels");
    expect(screen.queryByTestId("whatsapp-template-toggle")).toBeNull();
  });

  it("stores {name, language} and params sized to the template when one is picked", async () => {
    listWhatsAppTemplates.mockResolvedValue([reminder]);
    const setConfig = vi.fn();
    render(<WhatsAppTemplateFields config={{}} setConfig={setConfig} />);
    fireEvent.click(await screen.findByTestId("whatsapp-template-toggle"));
    fireEvent.change(screen.getByTestId("whatsapp-template-select"), { target: { value: templateKey(reminder) } });
    expect(setConfig).toHaveBeenCalledWith({
      whatsappTemplate: { name: "invoice_reminder", language: "en_US" },
      whatsappTemplateParams: ["", ""],
    });
  });

  it("shows one expression field per template variable and writes the whole params array", async () => {
    listWhatsAppTemplates.mockResolvedValue([reminder]);
    const setConfig = vi.fn();
    render(
      <WhatsAppTemplateFields
        config={{ whatsappTemplate: { name: "invoice_reminder", language: "en_US" }, whatsappTemplateParams: ["Asha"] }}
        setConfig={setConfig}
      />,
    );
    await screen.findByTestId("whatsapp-template-preview");
    expect((screen.getByTestId("expr-whatsappTemplateParams[0]") as HTMLInputElement).value).toBe("Asha");
    fireEvent.change(screen.getByTestId("expr-whatsappTemplateParams[1]"), { target: { value: "{{ $json.number }}" } });
    expect(setConfig).toHaveBeenCalledWith({ whatsappTemplateParams: ["Asha", "{{ $json.number }}"] });
    expect(screen.queryByTestId("expr-whatsappTemplateParams[2]")).toBeNull();
  });

  it("clears the template keys when the switch is turned off", async () => {
    listWhatsAppTemplates.mockResolvedValue([reminder]);
    const setConfig = vi.fn();
    render(
      <WhatsAppTemplateFields
        config={{ whatsappTemplate: { name: "invoice_reminder", language: "en_US" }, whatsappTemplateParams: ["a", "b"] }}
        setConfig={setConfig}
      />,
    );
    fireEvent.click(await screen.findByTestId("whatsapp-template-toggle"));
    expect(setConfig).toHaveBeenCalledWith({ whatsappTemplate: undefined, whatsappTemplateParams: undefined });
  });

  it("warns when the stored template is no longer approved, and keeps its params editable", async () => {
    listWhatsAppTemplates.mockResolvedValue([reminder]);
    render(
      <WhatsAppTemplateFields
        config={{ whatsappTemplate: { name: "old_promo", language: "en" }, whatsappTemplateParams: ["x"] }}
        setConfig={vi.fn()}
      />,
    );
    await screen.findByTestId("whatsapp-template-missing");
    expect((screen.getByTestId("whatsapp-template-select") as HTMLSelectElement).value).toBe(templateKey({ name: "old_promo", language: "en" }));
    expect(screen.getByTestId("expr-whatsappTemplateParams[0]")).toBeTruthy();
  });

  it("keeps a stored template visible, editable and switch-off-able when the listing fails", async () => {
    listWhatsAppTemplates.mockRejectedValue(httpError(403));
    const setConfig = vi.fn();
    render(
      <WhatsAppTemplateFields
        config={{ whatsappTemplate: { name: "invoice_reminder", language: "en_US" }, whatsappTemplateParams: ["Asha", "INV-1"] }}
        setConfig={setConfig}
      />,
    );
    const stored = await screen.findByTestId("whatsapp-template-stored");
    expect(stored.textContent).toContain("whatsappTemplateStored");
    expect(screen.getByTestId("whatsapp-templates-forbidden")).toBeTruthy();
    expect(screen.queryByTestId("whatsapp-template-select")).toBeNull();
    expect((screen.getByTestId("expr-whatsappTemplateParams[1]") as HTMLInputElement).value).toBe("INV-1");
    fireEvent.change(screen.getByTestId("expr-whatsappTemplateParams[0]"), { target: { value: "Ravi" } });
    expect(setConfig).toHaveBeenCalledWith({ whatsappTemplateParams: ["Ravi", "INV-1"] });
    fireEvent.click(screen.getByTestId("whatsapp-template-toggle"));
    expect(setConfig).toHaveBeenCalledWith({ whatsappTemplate: undefined, whatsappTemplateParams: undefined });
  });

  it("shows no stored-template block on a failed listing when nothing is stored", async () => {
    listWhatsAppTemplates.mockRejectedValue(httpError(500, "boom"));
    render(<WhatsAppTemplateFields config={{}} setConfig={vi.fn()} />);
    await screen.findByTestId("whatsapp-templates-error");
    expect(screen.queryByTestId("whatsapp-template-stored")).toBeNull();
    expect(screen.queryByTestId("whatsapp-template-toggle")).toBeNull();
  });

  it("keeps the picker open when the placeholder option is chosen on a stored template", async () => {
    listWhatsAppTemplates.mockResolvedValue([reminder]);
    const setConfig = vi.fn();
    const view = render(
      <WhatsAppTemplateFields
        config={{ whatsappTemplate: { name: "invoice_reminder", language: "en_US" }, whatsappTemplateParams: ["a", "b"] }}
        setConfig={setConfig}
      />,
    );
    await screen.findByTestId("whatsapp-template-preview");
    fireEvent.change(screen.getByTestId("whatsapp-template-select"), { target: { value: "" } });
    expect(setConfig).toHaveBeenCalledWith({ whatsappTemplate: undefined, whatsappTemplateParams: undefined });
    // The parent applies the patch: nothing stored any more, the picker must still be there.
    view.rerender(<WhatsAppTemplateFields config={{}} setConfig={setConfig} />);
    expect(screen.getByTestId("whatsapp-template-select")).toBeTruthy();
  });
});
