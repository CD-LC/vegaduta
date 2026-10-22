// The read-only view-as bar belongs to platform-operator impersonation; a personal install has no operator,
// so the banner is not mounted (nor its session revalidation) even if a stale session is in storage.
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const viewAs = vi.hoisted(() => ({
  state: {
    grant: "g1",
    session: { userEmail: "a@example.test", tenantName: "Acme", expiresAt: "2999-01-01T00:00:00Z" },
  } as unknown,
}));
const check = vi.hoisted(() => vi.fn());

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("../lib/useViewAs", () => ({ useViewAs: () => ({ active: true, readOnly: true, state: viewAs.state }) }));
vi.mock("../lib/auth", () => ({ auth: { isAuthenticated: () => true } }));
vi.mock("../lib/api/viewAsSession", () => ({ checkViewAsSession: check }));

import ViewAsBanner from "./ViewAsBanner";

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe("ViewAsBanner", () => {
  it("renders nothing and never revalidates on a personal install", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    check.mockResolvedValue({ kind: "error" });
    const { container } = render(<ViewAsBanner />);
    expect(container).toBeEmptyDOMElement();
    expect(check).not.toHaveBeenCalled();
  });

  it("shows the bar for an active session on a shared install", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    check.mockResolvedValue({ kind: "error" });
    render(<ViewAsBanner />);
    expect(screen.getByTestId("view-as-banner")).toBeTruthy();
  });
});
