// @vitest-environment jsdom
//
// The Court map's behaviour: five regions, hover shows the purpose and lights
// the real rail item, click walks the region (or opens its first page when it
// has no steps), and "Skip to briefing" is always there - including in the
// phone-width bottom-sheet variant.

import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { push, tour, billing } = vi.hoisted(() => ({
  billing: { on: true },
  push: vi.fn(),
  tour: {
    courtOpen: true,
    closeCourt: vi.fn(),
    walkCourtRegion: vi.fn((_id: string) => true),
    courtStopCount: vi.fn((_id: string) => 2),
  },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
  usePathname: () => "/build",
}));
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock("../TourProvider", () => ({ useTour: () => tour }));
vi.mock("../../lib/billing-flag", () => ({ isBillingEnabled: () => billing.on }));

import CourtMap from "./CourtMap";

function setWidth(w: number) {
  Object.defineProperty(window, "innerWidth", { configurable: true, value: w });
}

beforeEach(() => {
  push.mockClear();
  tour.closeCourt.mockClear();
  tour.walkCourtRegion.mockReset().mockReturnValue(true);
  tour.courtOpen = true;
  billing.on = true;
  setWidth(1400);
  document.body.innerHTML = '<nav data-tour="nav-rail"></nav><div data-tour="nav-group-build" id="rail-build"></div>';
});

afterEach(() => {
  cleanup();
  document.body.innerHTML = "";
});

describe("CourtMap (desktop plan)", () => {
  it("draws the five regions and always offers Skip to briefing", async () => {
    render(<CourtMap />);
    for (const id of ["build", "connect", "config", "monitor", "plans"]) {
      expect(await screen.findByTestId(`court-region-${id}`)).toBeTruthy();
    }
    expect(screen.getByTestId("court-skip").textContent).toBe("court.skip");
  });

  it("leaves out the Plans region when billing is off (no plans exist)", async () => {
    billing.on = false;
    render(<CourtMap />);
    expect(await screen.findByTestId("court-region-build")).toBeTruthy();
    expect(screen.queryByTestId("court-region-plans")).toBeNull();
  });

  it("shows the purpose on hover and lights the matching rail item, then restores it", async () => {
    render(<CourtMap />);
    const build = await screen.findByTestId("court-region-build");
    const rail = document.getElementById("rail-build") as HTMLElement;
    fireEvent.mouseEnter(build);
    expect(screen.getByTestId("court-caption").textContent).toContain("court.regions.build.purpose");
    expect(rail.style.outline).toContain("var(--primary)");
    fireEvent.mouseLeave(build);
    expect(rail.style.outline).toBe("");
  });

  it("click walks the region; with no steps it closes and opens the first page instead", async () => {
    render(<CourtMap />);
    fireEvent.click(await screen.findByTestId("court-region-monitor"));
    expect(tour.walkCourtRegion).toHaveBeenCalledWith("monitor");
    expect(push).not.toHaveBeenCalled();

    tour.walkCourtRegion.mockReturnValue(false);
    fireEvent.click(screen.getByTestId("court-region-plans"));
    expect(tour.closeCourt).toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith("/features");
  });

  it("Enter on a focused region walks it; Escape closes the court", async () => {
    render(<CourtMap />);
    const cfg = await screen.findByTestId("court-region-config");
    fireEvent.keyDown(cfg, { key: "Enter" });
    expect(tour.walkCourtRegion).toHaveBeenCalledWith("config");
    act(() => {
      window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });
    expect(tour.closeCourt).toHaveBeenCalled();
  });

  it("renders nothing while closed", () => {
    tour.courtOpen = false;
    render(<CourtMap />);
    expect(screen.queryByTestId("court-map")).toBeNull();
  });
});

describe("CourtMap (phone bottom sheet)", () => {
  it("swaps the plan for the same regions as a list, still with Skip to briefing", async () => {
    setWidth(500);
    render(<CourtMap />);
    expect(await screen.findByTestId("court-sheet")).toBeTruthy();
    expect(screen.queryByTestId("court-map")).toBeNull();
    expect(screen.getByTestId("court-skip")).toBeTruthy();
    fireEvent.click(screen.getByTestId("court-region-build"));
    expect(tour.walkCourtRegion).toHaveBeenCalledWith("build");
  });
});
