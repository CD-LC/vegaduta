import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import en from "../../../messages/en.json";

/** Opens a ui/Select (role=combobox) by its label and picks an option by visible text. */
function pick(label: string, option: string | RegExp) {
  fireEvent.click(screen.getByLabelText(label));
  fireEvent.click(screen.getByRole("option", { name: option }));
}

const priv = vi.hoisted(() => ({
  getConsentPolicy: vi.fn(),
  putConsentPolicy: vi.fn(),
  consentRecords: vi.fn(),
  listRetention: vi.fn(),
  putRetention: vi.fn(),
  getPurposes: vi.fn(),
  putPurposes: vi.fn(),
  exportContact: vi.fn(),
  eraseContact: vi.fn(),
  downloadAuditExport: vi.fn(),
}));
const baseApi = vi.hoisted(() => ({ listAgents: vi.fn(), listCollections: vi.fn() }));

vi.mock("../../lib/api/privacy", async (orig) => ({ ...((await orig()) as object), privacyApi: priv }));
vi.mock("../../lib/api", () => ({ api: baseApi }));
vi.mock("../../lib/api/core", () => ({ triggerBrowserDownload: vi.fn() }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children?: ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/RouteTransition", () => ({ usePageReady: (): void => undefined }));
vi.mock("../../components/useRequireScope", () => ({ useRequireScope: () => true }));
vi.mock("../../components/DataPrivacySection", () => ({ default: () => <div>workspace-section</div> }));
vi.mock("next/navigation", () => ({ usePathname: () => "/admin/data-privacy" }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import AuditExportTab, { auditRange } from "./AuditExportTab";
import ConsentPolicyTab, { validateConsent } from "./ConsentPolicyTab";
import ContactsTab from "./ContactsTab";
import PurposesTab from "./PurposesTab";
import RetentionTab from "./RetentionTab";
import Page from "./page";

beforeEach(() => {
  Object.values(priv).forEach((f) => f.mockReset());
  baseApi.listAgents.mockResolvedValue([{ id: "a1", name: "Support bot" }]);
  baseApi.listCollections.mockResolvedValue([{ id: "c1", name: "FAQ" }]);
});
afterEach(cleanup);

const policy = (over: Record<string, unknown> = {}) => ({
  agentId: "a1", enabled: true, purpose: "support", noticeVersion: 2, defaultLanguage: "en", notices: { en: "Hello" },
  instructions: {} as Record<string, string>, keywords: { accept: ["yes"] }, eraseOnWithdrawal: false, updatedBy: "admin", updatedAt: "2026-10-01T00:00:00Z", ...over,
});

describe("page", () => {
  it("shows the tabs and switches to the workspace section", async () => {
    priv.getConsentPolicy.mockResolvedValue(null);
    render(<Page />);
    for (const name of ["Consent policy", "Retention policy", "Purposes", "Contact requests", "Audit export", "Workspace"]) {
      expect(screen.getByRole("tab", { name })).toBeTruthy();
    }
    expect(screen.getByText(/not legal advice/)).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Workspace" }));
    expect(screen.getByText("workspace-section")).toBeTruthy();
  });
});

describe("consent policy tab", () => {
  it("validates like the server", () => {
    const base = { enabled: true, purpose: "support", defaultLanguage: "en", notices: [{ lang: "en", text: "x" }], instructions: [] as { lang: string; text: string }[] };
    expect(validateConsent(base)).toBeNull();
    expect(validateConsent({ ...base, purpose: "Bad Purpose" })).toBe("errPurpose");
    expect(validateConsent({ ...base, defaultLanguage: "english" })).toBe("errDefaultLanguage");
    expect(validateConsent({ ...base, notices: [] })).toBe("errNoticeMissing");
    expect(validateConsent({ ...base, enabled: false, notices: [] })).toBeNull();
    expect(validateConsent({ ...base, notices: [{ lang: "en", text: "x".repeat(3001) }] })).toBe("errNoticeLength");
  });

  it("loads a policy and saves it", async () => {
    priv.getConsentPolicy.mockResolvedValue(policy());
    priv.putConsentPolicy.mockResolvedValue(policy({ noticeVersion: 3 }));
    render(<ConsentPolicyTab />);
    await screen.findByLabelText("Agent");
    pick("Agent", "Support bot");
    await screen.findByText(/Notice version 2/);
    fireEvent.click(screen.getByRole("button", { name: "Save consent policy" }));
    await screen.findByText("Consent policy saved.");
    expect(priv.putConsentPolicy.mock.calls[0][1]).toMatchObject({ enabled: true, purpose: "support", notices: { en: "Hello" }, keywords: { accept: ["yes"] } });
  });

  it("blocks saving an enabled policy with no notice", async () => {
    priv.getConsentPolicy.mockResolvedValue(null);
    render(<ConsentPolicyTab />);
    await screen.findByLabelText("Agent");
    pick("Agent", "Support bot");
    await screen.findByText(/No consent policy is set/);
    fireEvent.click(screen.getByLabelText("Ask for consent before processing"));
    fireEvent.click(screen.getByRole("button", { name: "Save consent policy" }));
    await screen.findByText("Enabling consent needs a notice in the default language.");
    expect(priv.putConsentPolicy).not.toHaveBeenCalled();
  });

  it("shows an error with retry when agents fail to load", async () => {
    baseApi.listAgents.mockRejectedValueOnce(new Error("boom"));
    render(<ConsentPolicyTab />);
    await screen.findByText("boom");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByLabelText("Agent");
  });
});

describe("retention tab", () => {
  const rule = { channel: "*", dataClass: "TRANSCRIPT", enabled: true, retentionDays: 90, updatedBy: "a", updatedAt: null as string | null };
  it("lists rules and saves a new one", async () => {
    priv.listRetention.mockResolvedValue([rule]);
    priv.putRetention.mockResolvedValue({ rule, warnings: ["Heads up"] });
    render(<RetentionTab />);
    await screen.findByText("Message transcripts", { selector: "td" });
    fireEvent.change(screen.getByLabelText("Keep for (days)"), { target: { value: "30" } });
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));
    await screen.findByText("Retention rule saved.");
    expect(priv.putRetention).toHaveBeenCalledWith({ channel: "*", dataClass: "TRANSCRIPT", enabled: true, retentionDays: 30 });
    expect(screen.getByText("Heads up")).toBeTruthy();
  });
  it("rejects extracted fields for a single channel and out-of-range days", async () => {
    priv.listRetention.mockResolvedValue([]);
    render(<RetentionTab />);
    await screen.findByText("No retention rules");
    pick("Data", "Extracted fields (memory)");
    pick("Channel", "EMAIL");
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));
    await screen.findByText(/cannot be set for channel EMAIL/);
    pick("Channel", "Tenant default (all channels)");
    fireEvent.change(screen.getByLabelText("Keep for (days)"), { target: { value: "4000" } });
    fireEvent.click(screen.getByRole("button", { name: "Save rule" }));
    await screen.findByText(/must be a whole number between 1 and 3650/);
    expect(priv.putRetention).not.toHaveBeenCalled();
  });
  it("offers Slack, Discord, X and Teams as rule channels, except on a personal install", async () => {
    priv.listRetention.mockResolvedValue([]);
    const channelOptions = async () => {
      render(<RetentionTab />);
      await screen.findByText("No retention rules");
      fireEvent.click(screen.getByLabelText("Channel"));
      const names = screen.getAllByRole("option").map((o) => o.textContent);
      cleanup();
      return names;
    };
    const shared = await channelOptions();
    for (const c of ["SLACK", "DISCORD", "X", "TEAMS", "EMAIL"]) expect(shared).toContain(c);
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    try {
      const personal = await channelOptions();
      for (const c of ["SLACK", "DISCORD", "X", "TEAMS"]) expect(personal).not.toContain(c);
      for (const c of ["WHATSAPP", "TELEGRAM", "EMAIL", "SIGNAL"]) expect(personal).toContain(c);
    } finally {
      vi.unstubAllEnvs();
    }
  });
  it("shows an error with retry", async () => {
    priv.listRetention.mockRejectedValueOnce(new Error("nope")).mockResolvedValue([]);
    render(<RetentionTab />);
    await screen.findByText("nope");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    await screen.findByText("No retention rules");
  });
});

describe("purposes tab", () => {
  it("adds a valid purpose, rejects an invalid one, and saves", async () => {
    priv.getPurposes.mockResolvedValue(["support"]);
    priv.putPurposes.mockResolvedValue(["support", "billing"]);
    render(<PurposesTab />);
    await waitFor(() => expect(screen.getByLabelText("Resource").getAttribute("role")).toBe("combobox"));
    pick("Resource", "Support bot");
    await screen.findByText("support");
    fireEvent.change(screen.getByLabelText("Add a purpose"), { target: { value: "Bad Name" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    await screen.findByText(/Purpose must be 1-64/);
    fireEvent.change(screen.getByLabelText("Add a purpose"), { target: { value: "billing" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    fireEvent.click(screen.getByRole("button", { name: "Save purposes" }));
    await screen.findByText("Purposes saved.");
    expect(priv.putPurposes).toHaveBeenCalledWith("agents", "a1", ["support", "billing"]);
  });
});

describe("contacts tab", () => {
  it("needs an identifier, then erases only after confirmation", async () => {
    priv.eraseContact.mockResolvedValue({
      channel: "WHATSAPP", subjectMasked: "+91*****21", erasedAt: "2026-10-14T00:00:00Z", complete: false,
      erased: { messages: 3 }, failures: ["memory"], consentProofKept: true, notReached: ["Voice recordings"],
    });
    render(<ContactsTab />);
    fireEvent.click(screen.getByRole("button", { name: "Erase contact data" }));
    await screen.findByText("Enter the contact identifier.");
    fireEvent.change(screen.getByLabelText("Contact identifier"), { target: { value: "+919999999921" } });
    fireEvent.click(screen.getByRole("button", { name: "Erase contact data" }));
    const dialog = await screen.findByRole("alertdialog");
    expect(priv.eraseContact).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Erase permanently" }));
    await screen.findByText(/Erasure is incomplete/);
    expect(priv.eraseContact).toHaveBeenCalledWith({ channel: "WHATSAPP", externalId: "+919999999921", includeLeads: false, keepConsentProof: true });
    expect(screen.getByText("memory")).toBeTruthy();
    expect(screen.getByText("Voice recordings")).toBeTruthy();
  });
  it("exports and shows what was not included", async () => {
    priv.exportContact.mockResolvedValue({ channel: "EMAIL", subjectMasked: "a***@x.com", generatedAt: "2026-10-14T00:00:00Z", data: { messages: [1, 2] }, notIncluded: ["Device data"] });
    render(<ContactsTab />);
    fireEvent.change(screen.getByLabelText("Contact identifier"), { target: { value: "a@x.com" } });
    fireEvent.click(screen.getByRole("button", { name: "Export contact data" }));
    await screen.findByText("Device data");
    expect(screen.getByText("messages: 2")).toBeTruthy();
  });
  it("lists consent history", async () => {
    priv.consentRecords.mockResolvedValue([{ id: "r1", channel: "EMAIL", externalId: "x", agentId: null, purpose: "support", noticeVersion: 1, noticeLanguage: "en", noticeSha256: null, decision: "GRANTED", decidedAt: "2026-10-01T00:00:00Z" }]);
    render(<ContactsTab />);
    fireEvent.change(screen.getByLabelText("Contact identifier"), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "View consent history" }));
    await screen.findByText("GRANTED");
  });
});

describe("audit export tab", () => {
  it("computes the half-open range and enforces 366 days", () => {
    expect(auditRange("2026-01-01", "2026-01-01")).toEqual({ from: "2026-01-01T00:00:00.000Z", to: "2026-01-02T00:00:00.000Z" });
    expect(auditRange("2026-01-02", "2026-01-01")).toBe("order");
    expect(auditRange("2025-01-01", "2026-01-02")).toBe("range");
    expect(auditRange("2025-01-01", "2025-12-31")).not.toBe("range");
    expect(auditRange("", "2026-01-01")).toBe("missing");
  });
  it("downloads with the chosen format", async () => {
    priv.downloadAuditExport.mockResolvedValue(undefined);
    render(<AuditExportTab />);
    fireEvent.change(screen.getByLabelText("From (UTC)"), { target: { value: "2026-09-01" } });
    fireEvent.change(screen.getByLabelText("To (UTC, inclusive)"), { target: { value: "2026-09-30" } });
    pick("Format", "JSON");
    fireEvent.click(screen.getByRole("button", { name: "Download export" }));
    await screen.findByText("Export downloaded.");
    expect(priv.downloadAuditExport).toHaveBeenCalledWith("2026-09-01T00:00:00.000Z", "2026-10-01T00:00:00.000Z", "json");
  });
  it("refuses a range over the limit without calling the API", async () => {
    render(<AuditExportTab />);
    fireEvent.change(screen.getByLabelText("From (UTC)"), { target: { value: "2024-01-01" } });
    fireEvent.change(screen.getByLabelText("To (UTC, inclusive)"), { target: { value: "2026-01-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Download export" }));
    expect((await screen.findByRole("alert")).textContent).toMatch(/may not be longer than 366 days/);
    expect(priv.downloadAuditExport).not.toHaveBeenCalled();
  });
});
