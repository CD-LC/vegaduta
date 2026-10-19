import { afterEach, describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));
import { cleanup, render, screen } from "@testing-library/react";
import { ShareEndNotice, ShareLinkMeta, inferShareEndReason } from "./ShareLinkMeta";

afterEach(cleanup);

const base = { active: true, viewerCount: 2, reportCount: 0 };

describe("inferShareEndReason", () => {
  it("is null without a previously active link", () => {
    expect(inferShareEndReason(null)).toBeNull();
    expect(inferShareEndReason({ ...base, active: false })).toBeNull();
  });
  it("reports expiry, reports, or a plain end", () => {
    const now = new Date("2026-10-15T00:00:00Z");
    expect(inferShareEndReason({ ...base, expiresAt: "2026-10-14T00:00:00Z" }, now)).toBe("expired");
    expect(inferShareEndReason({ ...base, reportCount: 2, expiresAt: "2026-12-01T00:00:00Z" }, now)).toBe("reports");
    expect(inferShareEndReason(base, now)).toBe("ended");
  });
});

describe("ShareLinkMeta", () => {
  it("shows expiry and the report limit", () => {
    render(<ShareLinkMeta status={{ ...base, reportCount: 2, expiresAt: "2026-12-01T00:00:00Z" }} />);
    expect(screen.getByTestId("share-expiry").textContent).toMatch(/expires/i);
    expect(screen.getByTestId("share-reports").textContent).toMatch(/2 abuse report/);
  });
  it("says a legacy link never expires", () => {
    render(<ShareLinkMeta status={base} />);
    expect(screen.getByTestId("share-expiry").textContent).toMatch(/does not expire/);
  });
  it("renders the end notice only with a reason", () => {
    const { rerender } = render(<ShareEndNotice reason={null} />);
    expect(screen.queryByTestId("share-end-notice")).toBeNull();
    rerender(<ShareEndNotice reason="reports" />);
    expect(screen.getByTestId("share-end-notice").textContent).toMatch(/abuse reports/);
  });
});
