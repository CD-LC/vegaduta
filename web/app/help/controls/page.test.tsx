import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";
import snapshot from "../../lib/api/fixtures/settingsCatalog.snapshot.json";
import type { SettingCatalogEntry } from "../../lib/api/settings";
import { I18nProvider } from "../../lib/i18n";
import { makeClient } from "../../components/settings/settingsTestKit";

vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/AuthProvider", () => ({ useAuth: () => ({ isAdmin: true, hasPermission: () => true }) }));
vi.mock("next/navigation", () => ({ usePathname: () => "/help/controls" }));

import type * as Settings from "../../lib/api/settings";
const fake = makeClient({ catalog: snapshot.entries as unknown as SettingCatalogEntry[] });
vi.mock("../../lib/api/settings", async (importActual) => {
  const actual = await importActual<typeof Settings>();
  return { ...actual, get settingsApi() { return fake; } };
});

import ControlsHelpPage from "./page";

afterEach(() => cleanup());

describe("/help/controls", () => {
  it("has one h1 and lists the settings built from the catalog", async () => {
    const m = en as Record<string, unknown>;
    render(
      <I18nProvider locale="en" messages={m} enMessages={m} complete>
        <ControlsHelpPage />
      </I18nProvider>
    );
    expect(screen.getByRole("heading", { level: 1, name: /Where do I control/ })).toBeInTheDocument();
    expect(await screen.findByTestId("control-setting-guardrails.failMode")).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  });
});
