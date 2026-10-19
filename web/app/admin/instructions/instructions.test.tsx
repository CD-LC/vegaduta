// Instructions console against a mocked API; the translator reads the real messages/en.json.
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";

const api = vi.hoisted(() => ({
  getPromptDirectives: vi.fn(),
  savePromptDirective: vi.fn(),
  resetPromptDirective: vi.fn(),
  saveCustomInstructions: vi.fn(),
}));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/useRequireScope", () => ({ useRequireScope: () => true }));

import AdminInstructionsPage from "./page";
import type { PromptDirective } from "../../lib/api/promptDirectives";

const t = en.promptDirectives;
const plain: PromptDirective = {
  key: "date", title: "Current date", description: "Tells the model today's date.", securityCritical: false,
  placeholders: ["{date}"], enabled: true, overridden: false,
  defaultText: "Today is {date}.", overrideText: null, effectiveText: "Today is {date}.",
};
const critical: PromptDirective = {
  key: "safety", title: "Safety rules", description: "Refuse harmful requests.", securityCritical: true,
  placeholders: [], enabled: true, overridden: true,
  defaultText: "Refuse harm.", overrideText: "Refuse harm, politely.", effectiveText: "Refuse harm, politely.",
};
const overview = {
  directives: [plain, critical],
  customInstructions: { enabled: true, text: "Be brief." },
  assembledPreview: "ASSEMBLED PROMPT TEXT",
};

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
  api.getPromptDirectives.mockResolvedValue(overview);
  api.savePromptDirective.mockResolvedValue({});
  api.resetPromptDirective.mockResolvedValue({});
  api.saveCustomInstructions.mockResolvedValue({});
});
afterEach(cleanup);

async function openCard(title: string) {
  render(<AdminInstructionsPage />);
  await screen.findByText(title);
  const card = screen.getByTestId(title === plain.title ? "directive-date" : "directive-safety");
  fireEvent.click(card.querySelector("button[aria-expanded]") as HTMLElement);
  return card;
}

describe("AdminInstructionsPage", () => {
  it("renders custom instructions, badges and the preview", async () => {
    render(<AdminInstructionsPage />);
    expect(await screen.findByText(plain.title)).toBeTruthy();
    expect((screen.getByLabelText(t.customLabel) as HTMLTextAreaElement).value).toBe("Be brief.");
    expect(screen.getByText(t.default)).toBeTruthy();
    expect(screen.getByText(t.customised)).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: t.tabPreview }));
    expect(screen.getByText("ASSEMBLED PROMPT TEXT")).toBeTruthy();
  });

  it("saves custom instructions with the typed body, disabled until changed", async () => {
    render(<AdminInstructionsPage />);
    await screen.findByText(plain.title);
    const save = screen.getAllByRole("button", { name: t.save })[0] as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(t.customLabel), { target: { value: "Be very brief." } });
    expect(save.disabled).toBe(false);
    api.getPromptDirectives.mockResolvedValue({ ...overview, customInstructions: { enabled: true, text: "Be very brief." } });
    fireEvent.click(save);
    await waitFor(() => expect(api.saveCustomInstructions).toHaveBeenCalledWith({ enabled: true, text: "Be very brief." }));
    expect(await screen.findByText(t.saved)).toBeTruthy();
  });

  it("edits a directive and saves the new text without a risk acknowledgement", async () => {
    const card = await openCard(plain.title);
    fireEvent.change(card.querySelector("textarea") as HTMLElement, { target: { value: "It is {date}." } });
    fireEvent.click(within(card).getByRole("button", { name: t.save }));
    await waitFor(() =>
      expect(api.savePromptDirective).toHaveBeenCalledWith("date", { enabled: true, text: "It is {date}.", acknowledgeRisk: false }),
    );
  });

  it("restores the default through the API for a customised directive", async () => {
    const card = await openCard(critical.title);
    fireEvent.click(within(card).getByRole("button", { name: t.restoreDefault }));
    await waitFor(() => expect(api.resetPromptDirective).toHaveBeenCalledWith("safety"));
  });

  it("will not save a security-critical change until the risk is confirmed", async () => {
    const card = await openCard(critical.title);
    fireEvent.change(card.querySelector("textarea") as HTMLElement, { target: { value: "Refuse nothing." } });
    const save = Array.from(card.querySelectorAll("button")).find((b) => b.textContent === t.save) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(within(card).getByRole("checkbox", { name: t.riskConfirm }));
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    await waitFor(() =>
      expect(api.savePromptDirective).toHaveBeenCalledWith("safety", { enabled: true, text: "Refuse nothing.", acknowledgeRisk: true }),
    );
  });

  it("shows the server's message when a save fails", async () => {
    api.savePromptDirective.mockRejectedValue(new Error("Acknowledge the risk first"));
    const card = await openCard(plain.title);
    fireEvent.change(card.querySelector("textarea") as HTMLElement, { target: { value: "x" } });
    fireEvent.click(Array.from(card.querySelectorAll("button")).find((b) => b.textContent === t.save) as HTMLElement);
    expect(await screen.findByText("Acknowledge the risk first")).toBeTruthy();
  });

  it("shows an error when the instructions cannot be loaded", async () => {
    api.getPromptDirectives.mockRejectedValue(new Error("boom"));
    render(<AdminInstructionsPage />);
    expect(await screen.findByText("boom")).toBeTruthy();
  });
});
