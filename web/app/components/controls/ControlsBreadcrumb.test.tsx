import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";
import { I18nProvider } from "../../lib/i18n";

let pathname = "/admin/runtime-settings";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));
const auth = { isAdmin: true, hasPermission: (_: string) => true };
vi.mock("../AuthProvider", () => ({ useAuth: () => auth }));

import ControlsBreadcrumb from "./ControlsBreadcrumb";

const MESSAGES = en as Record<string, unknown>;
const view = () =>
  render(
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      <ControlsBreadcrumb />
    </I18nProvider>
  );

afterEach(() => {
  cleanup();
  pathname = "/admin/runtime-settings";
  auth.isAdmin = true;
  auth.hasPermission = () => true;
});

describe("ControlsBreadcrumb", () => {
  it("shows Console > Controls > this page, with the page marked as current", () => {
    view();
    const crumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(crumbs).getByRole("link", { name: "Console" })).toHaveAttribute("href", "/");
    expect(within(crumbs).getByRole("link", { name: "Controls" })).toHaveAttribute("href", "/help/controls");
    expect(within(crumbs).getByText("Runtime settings")).toHaveAttribute("aria-current", "page");
  });

  it("links to every other control area and not to itself", () => {
    pathname = "/admin/approvals";
    view();
    const other = screen.getByRole("navigation", { name: "Other controls" });
    const hrefs = within(other).getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/admin/runtime-settings", "/admin/instructions", "/admin/guardrails", "/agents/turns", "/agents?section=behavior"]);
  });

  it("leaves out pages the person cannot open", () => {
    auth.isAdmin = false;
    auth.hasPermission = (n) => n === "VIEW_AUDIT_LOG";
    pathname = "/agents/turns";
    view();
    const other = screen.getByRole("navigation", { name: "Other controls" });
    expect(within(other).getAllByRole("link").map((a) => a.getAttribute("href"))).toEqual(["/agents?section=behavior"]);
  });

  it("renders nothing on other pages", () => {
    pathname = "/workflows";
    const { container } = view();
    expect(container).toBeEmptyDOMElement();
  });
});
