import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("next/link", () => ({ default: ({ children, href, ...rest }: any) => <a href={href} {...rest}>{children}</a> }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
    const raw = ((en as any)[ns] ?? {})[key];
    if (typeof raw !== "string") return `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (m, k) => (values && k in values ? String(values[k]) : m));
  },
}));

import WorkflowsGetStarted from "./WorkflowsGetStarted";

describe("WorkflowsGetStarted", () => {
  beforeEach(() => window.localStorage.clear());

  it("shows the recipes banner and featured templates, and can be hidden and restored", () => {
    render(<WorkflowsGetStarted />);
    expect(screen.getByText("New here? Start from an industry recipe")).toBeTruthy();
    expect(screen.getAllByRole("link").some((a) => a.getAttribute("href")?.startsWith("/workflows?template="))).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: /Hide/ }));
    expect(screen.queryByText("New here? Start from an industry recipe")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show get started" }));
    expect(screen.getByText("New here? Start from an industry recipe")).toBeTruthy();
  });
});
