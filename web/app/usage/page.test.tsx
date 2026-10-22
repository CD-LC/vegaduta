// /usage keeps its no-limit token view on every edition, but a personal install has no billing: no budget
// top-up card (and no /api/budget call behind it) and no link to the /roi billing page.
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

const api = vi.hoisted(() => ({ getUsageQuota: vi.fn(), getSpendProtection: vi.fn() }));
const budgetApi = vi.hoisted(() => ({ getBudgetTopUpOptions: vi.fn() }));

vi.mock("../lib/api", () => ({ api }));
vi.mock("../lib/api/budgetTopUp", () => ({ budgetTopUpApi: budgetApi }));
vi.mock("../components/AppShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));
vi.mock("../components/RouteTransition", () => ({ usePageReady: (): void => undefined }));
vi.mock("../components/AuthProvider", () => ({ useAuth: () => ({ hasPermission: () => false }) }));
vi.mock("../components/SpendCapCard", () => ({ default: (): null => null }));
vi.mock("../components/billing/LlmSpendSection", () => ({ default: (): null => null }));
vi.mock("../lib/i18n", () => ({ useTranslations: (ns: string) => (key: string) => `${ns}.${key}` }));
// The card itself is covered by BudgetTopUpCard.test.tsx; here only whether the page mounts it.
vi.mock("./BudgetTopUpCard", () => ({ default: () => <div data-testid="budget-top-up-card" /> }));

import UsagePage from "./page";

// BYOK keeps the meters out of the render; the cards under test sit above them.
const QUOTA = { plan: "FREE", usesPlatformPool: false, limitEnforced: false };

beforeEach(() => {
  api.getUsageQuota.mockResolvedValue(QUOTA);
  api.getSpendProtection.mockResolvedValue(null);
  budgetApi.getBudgetTopUpOptions.mockResolvedValue({ enabled: false, options: [] });
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

async function rendered() {
  const view = render(<UsagePage />);
  await waitFor(() => expect(api.getUsageQuota).toHaveBeenCalled());
  await waitFor(() => expect(view.container.querySelector("[data-slot='skeleton']")).toBeNull());
  return view;
}

describe("UsagePage on a personal install", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_EDITION", "personal"));

  it("has no top-up card, makes no budget call and links nowhere near /roi", async () => {
    const { container } = await rendered();
    expect(screen.queryByTestId("budget-top-up-card")).toBeNull();
    expect(budgetApi.getBudgetTopUpOptions).not.toHaveBeenCalled();
    expect(container.querySelectorAll("a[href='/roi']")).toHaveLength(0);
    expect(container.querySelector("a[href='/insights']")).not.toBeNull();
  });

  it("does not mount the top-up card even if billing is switched on by mistake", async () => {
    vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
    await rendered();
    expect(screen.queryByTestId("budget-top-up-card")).toBeNull();
  });
});

describe("UsagePage on a shared install", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_EDITION", "full"));

  it("mounts the top-up card when billing is on and links to /roi", async () => {
    vi.stubEnv("NEXT_PUBLIC_BILLING_ENABLED", "1");
    const { container } = await rendered();
    expect(screen.getByTestId("budget-top-up-card")).toBeTruthy();
    expect(container.querySelector("a[href='/roi']")).not.toBeNull();
  });

  it("mounts the top-up card even while billing is off (it hides itself when the server says top-ups are off)", async () => {
    await rendered();
    expect(screen.getByTestId("budget-top-up-card")).toBeTruthy();
  });

  it("mounts the top-up card on lite and on an undeclared install too", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "lite");
    await rendered();
    expect(screen.getByTestId("budget-top-up-card")).toBeTruthy();
    cleanup();
    vi.stubEnv("NEXT_PUBLIC_EDITION", "");
    await rendered();
    expect(screen.getByTestId("budget-top-up-card")).toBeTruthy();
  });
});
