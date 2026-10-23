// Accessibility (axe) of the "Where do I control...?" finder (full catalog, searching, no match, load error) and of
// the breadcrumb strip on a control page.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import { axeViolations } from "../../lib/axeTestKit";
import { I18nProvider } from "../../lib/i18n";
import type { SettingCatalogEntry } from "../../lib/api/settings";
import { makeClient } from "../settings/settingsTestKit";

vi.mock("next/navigation", () => ({ usePathname: () => "/admin/guardrails" }));
const auth = { isAdmin: true, hasPermission: (_: string) => true };
vi.mock("../AuthProvider", () => ({ useAuth: () => auth }));

import ControlsFinder from "./ControlsFinder";
import ControlsBreadcrumb from "./ControlsBreadcrumb";

const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES = en as Record<string, unknown>;
const view = (ui: ReactElement) =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      {ui}
    </I18nProvider>
  );

afterEach(() => cleanup());

describe("controls accessibility (axe)", () => {
  it("the finder with the whole catalog, then a search with matches, then none", async () => {
    view(<ControlsFinder client={makeClient({ catalog: ENTRIES })} />);
    await screen.findByTestId("control-setting-agent.limits.maxToolSteps");
    expect(await axeViolations()).toEqual([]);
    const box = screen.getByRole("searchbox", { name: "Search controls" });
    fireEvent.change(box, { target: { value: "approval" } });
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(box, { target: { value: "zzzzqq" } });
    expect(await axeViolations()).toEqual([]);
  });

  it("the finder while loading and after a failed load", async () => {
    const client = makeClient({ catalog: ENTRIES });
    client.getCatalog.mockRejectedValue(new Error("offline"));
    view(<ControlsFinder client={client} />);
    expect(await axeViolations()).toEqual([]);
    await screen.findByTestId("controls-error");
    expect(await axeViolations()).toEqual([]);
  });

  it("the breadcrumb and the other-controls strip", async () => {
    view(<ControlsBreadcrumb />);
    expect(screen.getByTestId("controls-wayfinding")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });
});
