import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../lib/useRegion", () => ({ useRegion: () => ({ isIndia: false }) }));
vi.mock("../lib/api", () => ({ api: {} }));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>,
}));

import UpgradePrompt from "./UpgradePrompt";
import { UsageAlertBanner } from "./UsageQuota";
import type { TenantUsageQuotaResponse } from "../lib/api";

// Billing switch: every upgrade surface is absent when
// NEXT_PUBLIC_BILLING_ENABLED is unset and unchanged when it is "1".
afterEach(() => {
  delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
});

describe("UpgradePrompt", () => {
  it("renders nothing when billing is off, even when open", () => {
    render(<UpgradePrompt open onClose={() => {}} reason="limit hit" />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("renders the plan dialog when billing is on", async () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    render(<UpgradePrompt open onClose={() => {}} reason="limit hit" />);
    expect(await screen.findByRole("dialog")).toBeTruthy();
    expect(screen.getByText("limit hit")).toBeTruthy();
  });
});


describe("UsageAlertBanner", () => {
  const quota: TenantUsageQuotaResponse = {
    plan: "FREE",
    usesPlatformPool: true,
    tokensUsedMonth: 100,
    tokensMonthlyLimit: 100,
  };

  it("keeps the limit warning but drops the upgrade CTA when off", async () => {
    render(<UsageAlertBanner quota={quota} canManageBilling />);
    expect(await screen.findByText("bannerOverTitle")).toBeTruthy();
    expect(screen.queryByText("bannerCta")).toBeNull();
  });

  it("shows the upgrade CTA to the billing page when on", async () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    render(<UsageAlertBanner quota={quota} canManageBilling />);
    const cta = await screen.findByText("bannerCta");
    expect(cta.closest("a")?.getAttribute("href")).toBe("/admin/billing");
  });
});
