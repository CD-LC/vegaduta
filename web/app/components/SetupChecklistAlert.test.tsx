// Prod re-verification 2026-10-05, R6: at 375px the topbar pill "Get set up 2/4" was squeezed and wrapped to three
// lines (118x74), pushing the workspace name to "RU...". jsdom has no layout, so this pins the rules that keep it on
// one line: it never wraps or shrinks, and below the sm breakpoint the words give way to the icon and the count
// while the accessible name keeps them.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, unknown>) => (v ? `${key}:${Object.values(v).join("/")}` : key),
}));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));
vi.mock("./TourProvider", () => ({ useTour: () => ({ status: "idle" }), isTourPending: () => false }));

import SetupChecklistAlert from "./SetupChecklistAlert";

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

const steps = [
  { key: "agent", href: "/agents", label: "Create an agent", hint: "h", done: true },
  { key: "channel", href: "/admin/channels", label: "Connect a channel", hint: "h", done: true },
  { key: "user", href: "/admin/access", label: "Invite a teammate", hint: "h", done: false },
  { key: "provider", href: "/providers", label: "Add a provider", hint: "h", done: false },
];

describe("SetupChecklistAlert - the topbar pill stays on one line", () => {
  it("never wraps or shrinks, and keeps the count", () => {
    // The popover auto-opens once per browser; mark it as seen so only the pill is under test.
    window.localStorage.setItem("adminOverview.setupAlert.autoOpened.v1", "1");
    const { container } = render(<SetupChecklistAlert steps={steps} allDone={false} />);
    const pill = screen.getByRole("button", { name: "getSetup 2/4" });
    expect(pill.className).toContain("whitespace-nowrap");
    expect(pill.className).toContain("shrink-0");
    expect(container.firstElementChild?.className).toContain("shrink-0");
    expect(pill.textContent).toContain("2/4");
  });

  it("drops the words, not the count, on a phone", () => {
    window.localStorage.setItem("adminOverview.setupAlert.autoOpened.v1", "1");
    render(<SetupChecklistAlert steps={steps} allDone={false} />);
    const label = screen.getByText("getSetup");
    expect(label.className).toContain("hidden");
    expect(label.className).toContain("sm:inline");
  });

  it("renders nothing once everything is done", () => {
    const { container } = render(<SetupChecklistAlert steps={steps.map((s) => ({ ...s, done: true }))} allDone />);
    expect(container.textContent).toBe("");
  });
});
