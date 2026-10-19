// Round 4: first login as a consumer (C1-C4).
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import {
  ALL_MESSAGES,
  DEFAULT_SETTINGS,
  FakeShell,
  Harness,
  NEW_SIGNUP,
  TOUR_MESSAGES,
  activeContext,
  expectNothingBlocksTheApp,
  mock,
  push,
  renderTour,
  setTour,
  settle,
  stepById,
} from "./tourTestSupport";
import { describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import TourOverlay from "./TourOverlay";
import { MIN_TOUR_STEPS } from "./audience";
import { TourProvider } from "../TourProvider";
import { AuthContext } from "../AuthProvider";
import TourReopenButton from "./TourReopenButton";
import { CUSTOM_AGENT_CTA, HOSTED_MODEL_NOTE, REOPEN_HINT } from "./consumerGuide";

describe("C1 - the hosted-model fact reaches the person it was written for", () => {
  it("tells a grant-holding new signup no API key is needed, because the SERVER said so", async () => {
    // The bug: plan UNLIMITED -> `startPaid*` wording, which never makes the
    // claim. Same root cause as providerGate's "No active LLM provider" banner.
    mock.availability = () =>
      Promise.resolve({ hasByokProvider: false, platformHostedAvailable: true, canCreateAgents: true });
    renderTour({ tenantSettings: NEW_SIGNUP });
    fireEvent.click(screen.getByTestId("start"));
    await settle();

    expect(screen.getByTestId("tour-hosted-note")).toHaveTextContent(HOSTED_MODEL_NOTE);
    // Only on the opening step.
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
  });

  it("says NOTHING when the answer is unknown and the plan is not FREE", async () => {
    // A paid workspace past its grant with the hosted toggle off genuinely
    // needs a key. A failed request must not turn into a false promise - and
    // must not break the card either.
    mock.availability = () => Promise.reject(new Error("503"));
    renderTour({ tenantSettings: NEW_SIGNUP });
    fireEvent.click(screen.getByTestId("start"));
    await settle();

    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
    expect(screen.getByRole("heading", { name: "startTitle" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "next" })).toBeInTheDocument();
  });

  it("says nothing when the server answers that the pool is NOT available", async () => {
    mock.availability = () =>
      Promise.resolve({ hasByokProvider: true, platformHostedAvailable: false, canCreateAgents: true });
    renderTour({ tenantSettings: NEW_SIGNUP });
    fireEvent.click(screen.getByTestId("start"));
    await settle();
    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
  });

  it("still makes the claim on a positively FREE plan when the request fails", async () => {
    // Core routes every FREE tenant to the hosted pool regardless of any flag.
    mock.availability = () => Promise.reject(new Error("offline"));
    renderTour({ tenantSettings: { ...NEW_SIGNUP, plan: "FREE" } });
    fireEvent.click(screen.getByTestId("start"));
    await settle();
    expect(screen.getByTestId("tour-hosted-note")).toBeInTheDocument();
  });

  it("survives a wrapper that throws synchronously, and a garbage response", async () => {
    mock.availability = () => {
      throw new Error("sync throw");
    };
    renderTour({ tenantSettings: NEW_SIGNUP });
    fireEvent.click(screen.getByTestId("start"));
    await settle();
    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
    cleanup();

    mock.availability = () => Promise.resolve("<html>gateway timeout</html>");
    renderTour({ tenantSettings: NEW_SIGNUP });
    fireEvent.click(screen.getByTestId("start"));
    await settle();
    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
  });

  it("never asks on behalf of a platform-admin, who may have no tenant", async () => {
    const spy = vi.fn(() => Promise.resolve({ platformHostedAvailable: true }));
    mock.availability = spy;
    renderTour({ isAdmin: false, isPlatformAdmin: true, tenantSettings: null });
    fireEvent.click(screen.getByTestId("start"));
    await settle();
    expect(spy).not.toHaveBeenCalled();
    expect(screen.queryByTestId("tour-hosted-note")).toBeNull();
  });
});

describe("C2 - the Edge AI step", () => {
  it("renders its own English instead of a raw key path, with a Show me to /edge", () => {
    setTour(activeContext("edge", { index: 4, total: 7 }));
    render(<TourOverlay />);

    const step = stepById("edge");
    expect(screen.getByRole("heading", { name: step.title })).toBeInTheDocument();
    expect(screen.getByText(step.body)).toBeInTheDocument();
    expect(screen.getByText(step.points[0])).toBeInTheDocument();
    expect(screen.getByText(step.points[1])).toBeInTheDocument();
    // The failure this guards: "edgeTitle" / "tour.edgeTitle" on screen.
    expect(screen.queryByText(/edgeTitle/)).toBeNull();
    expect(screen.queryByText(/edgeBody/)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "showMe" }));
    expect(push).toHaveBeenCalledWith("/edge");
  });

  it("prefers the translated key the day it exists", () => {
    // Key-first: adding tour.edgeTitle to en.json must win with no code change.
    mock.realCopy = true;
    const original = mock.messages;
    mock.messages = {
      ...ALL_MESSAGES,
      tour: { ...TOUR_MESSAGES, edgeTitle: "Edge-KI auf deinem Gerät" },
    };
    try {
      setTour(activeContext("edge", { index: 4, total: 7 }));
      render(<TourOverlay />);
      expect(screen.getByRole("heading", { name: "Edge-KI auf deinem Gerät" })).toBeInTheDocument();
      // The three keys that do NOT exist still fall back, not to a dotted path.
      expect(screen.getByText(stepById("edge").body)).toBeInTheDocument();
      expect(screen.queryByText(/tour\.edge/)).toBeNull();
    } finally {
      mock.messages = original;
    }
  });

  it("anchors on the rail's own /edge link when no data-tour marker exists", () => {
    // AppShell has no "nav-edge" marker today. Without the fallback, rule 4
    // would delete this step for every visitor, silently.
    mock.tenantSettings = { ...DEFAULT_SETTINGS };
    render(
      <AuthContext.Provider value={{ isAdmin: true, isPlatformAdmin: false } as unknown as null}>
        <TourProvider>
          <FakeShell omit={["nav-edge", "nav-rail"]} />
          <nav data-tour="nav-rail">
            <a href="/edge">Edge AI</a>
          </nav>
          <Harness />
        </TourProvider>
      </AuthContext.Provider>
    );
    fireEvent.click(screen.getByTestId("start"));
    expect((screen.getByTestId("ids").textContent ?? "").split(",")).toContain("edge");
  });

  it("drops the step when the rail has neither the marker nor the link", () => {
    renderTour({ omit: ["nav-edge"] });
    fireEvent.click(screen.getByTestId("start"));
    const got = (screen.getByTestId("ids").textContent ?? "").split(",");
    expect(got).not.toContain("edge");
    expect(got).toContain("playground");
  });
});

describe("C3 - a missing anchor never strands the visitor", () => {
  /** A provider with NO shell at all: every anchored step fails rule 4 and the
   * normal selection is []. This is the state after an unlucky navigation. */
  function renderWithoutShell() {
    mock.tenantSettings = { ...DEFAULT_SETTINGS };
    return render(
      <AuthContext.Provider value={{ isAdmin: true, isPlatformAdmin: false } as unknown as null}>
        <TourProvider>
          <Harness />
          <TourOverlay />
        </TourProvider>
      </AuthContext.Provider>
    );
  }

  it('"Take the tour" with no anchors on screen starts a walk of centred cards, not nothing', () => {
    // Before: startTour() resolved [], set status idle, and the hero closed on
    // a button that visibly did nothing.
    renderWithoutShell();
    fireEvent.click(screen.getByTestId("start"));

    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(Number(screen.getByTestId("total").textContent)).toBeGreaterThanOrEqual(MIN_TOUR_STEPS);
    // Audience rules still hold when only reachability is waived.
    const got = (screen.getByTestId("ids").textContent ?? "").split(",");
    expect(got).not.toContain("shared-with-me");
    expect(got).not.toContain("platform-console");

    // Every card is advanceable and dismissable: walk to the end.
    const total = Number(screen.getByTestId("total").textContent);
    for (let i = 1; i < total; i += 1) {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      // No spotlight to cut a hole with -> the scrim, never a bare modal.
      expect(document.body.querySelector('[class*="bg-black/55"]')).not.toBeNull();
      fireEvent.click(screen.getByRole("button", { name: "next" }));
    }
    fireEvent.click(screen.getByRole("button", { name: "finish" }));
    expect(screen.getByTestId("status")).toHaveTextContent("completed");
  });

  it("Escape always gets out, from a card with no anchor", () => {
    renderWithoutShell();
    fireEvent.click(screen.getByTestId("start"));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(screen.queryByRole("dialog")).toBeNull();
    expectNothingBlocksTheApp();
  });

  it("renders no overlay at all if the context says active with no step", () => {
    // The production-outage shape: a scrim with no card. `isActive` requires a
    // step, so a null currentStep must paint NOTHING rather than a bare dimmer.
    setTour({ status: "active", currentStep: null, totalSteps: 0 });
    render(<TourOverlay />);
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.body.querySelector('[class*="bg-black/5"]')).toBeNull();
    expectNothingBlocksTheApp();
  });

  it("stops blocking the app in the same commit the tour ends, not when a fade-out finishes", () => {
    // The dimmers sit inside AnimatePresence, which keeps a node mounted until
    // its exit animation completes - and under jsdom (as in a backgrounded tab)
    // it may never complete. They used to be the click-catchers too, so an
    // ended tour left an invisible full-viewport layer eating every click.
    renderWithoutShell();
    fireEvent.click(screen.getByTestId("start"));
    expect(document.body.querySelector('[data-tour-blocker="step"]')).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "skip" }));
    expectNothingBlocksTheApp();
    cleanup();

    // Same for both modals, and their backdrops still dismiss on a click.
    const dismissWelcome = vi.fn();
    setTour({ status: "welcome", dismissWelcome, startTour: vi.fn() });
    const view = render(<TourOverlay />);
    fireEvent.click(document.body.querySelector('[data-tour-blocker="welcome"]') as Element);
    expect(dismissWelcome).toHaveBeenCalled();
    setTour({ status: "idle", currentStep: null });
    view.rerender(<TourOverlay />);
    expectNothingBlocksTheApp();
    cleanup();

    const finishTour = vi.fn();
    setTour({ status: "completed", finishTour });
    const done = render(<TourOverlay />);
    fireEvent.click(document.body.querySelector('[data-tour-blocker="completed"]') as Element);
    expect(finishTour).toHaveBeenCalled();
    setTour({ status: "idle", currentStep: null });
    done.rerender(<TourOverlay />);
    expectNothingBlocksTheApp();
  });
});

describe("C4 - dismiss, and find the way back", () => {
  function HarnessWithReopen() {
    return (
      <>
        <Harness />
        <TourReopenButton />
      </>
    );
  }

  function renderWithReopen(opts: { isAdmin?: boolean; isPlatformAdmin?: boolean } = {}) {
    const { isAdmin = true, isPlatformAdmin = false } = opts;
    mock.tenantSettings = { ...DEFAULT_SETTINGS };
    return render(
      <AuthContext.Provider value={{ isAdmin, isPlatformAdmin } as unknown as null}>
        <TourProvider>
          <FakeShell />
          <HarnessWithReopen />
          <TourOverlay />
        </TourProvider>
      </AuthContext.Provider>
    );
  }

  it("tells someone who skipped half-way where the tour went, without blocking the app", () => {
    renderWithReopen();
    fireEvent.click(screen.getByTestId("start"));
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    fireEvent.click(screen.getByRole("button", { name: "skip" }));

    const hint = screen.getByTestId("tour-reopen-hint");
    expect(hint).toHaveTextContent(REOPEN_HINT);
    // A status note, not a modal: no dialog, no scrim, nothing trapping focus.
    expect(hint).toHaveAttribute("role", "status");
    expect(screen.queryByRole("dialog")).toBeNull();
    expectNothingBlocksTheApp();
    // Its wrapper must not swallow clicks meant for the app underneath.
    expect(hint.parentElement?.className).toContain("pointer-events-none");

    fireEvent.click(screen.getByRole("button", { name: "closeLabel" }));
    expect(screen.queryByTestId("tour-reopen-hint")).toBeNull();
  });

  it("takes the note down by itself", () => {
    vi.useFakeTimers();
    try {
      renderWithReopen();
      fireEvent.click(screen.getByTestId("dismiss"));
      expect(screen.getByTestId("tour-reopen-hint")).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(10_000);
      });
      expect(screen.queryByTestId("tour-reopen-hint")).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not show the note after a FINISHED tour - the completion card already said it", () => {
    renderWithReopen();
    fireEvent.click(screen.getByTestId("jump-high"));
    fireEvent.click(screen.getByRole("button", { name: "finish" }));
    expect(screen.getByText(REOPEN_HINT)).toBeInTheDocument(); // on the completion card
    fireEvent.click(screen.getByRole("button", { name: "completedSecondary" }));
    expect(screen.queryByTestId("tour-reopen-hint")).toBeNull();
  });

  it("the ? button reopens a skipped tour from step one, and clears the note", () => {
    renderWithReopen();
    fireEvent.click(screen.getByTestId("start"));
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    fireEvent.click(screen.getByRole("button", { name: "skip" }));
    expect(window.localStorage.getItem("vd_tour_status")).toBe("skipped");

    fireEvent.click(screen.getByRole("button", { name: "reopenLabel" }));
    fireEvent.click(screen.getByRole("button", { name: /Replay the product tour/ }));

    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.getByTestId("index")).toHaveTextContent("0");
    expect(screen.queryByTestId("tour-reopen-hint")).toBeNull();
    // The panel got out of the way of the tour it started.
    expect(screen.queryByTestId("tour-next-panel")).toBeNull();
  });

  it('answers "what should I do next?" with this audience\'s moves, and no dead ends', () => {
    renderWithReopen();
    fireEvent.click(screen.getByRole("button", { name: "reopenLabel" }));
    const panel = screen.getByTestId("tour-next-panel");
    const hrefs = Array.from(panel.querySelectorAll("a")).map((a) => a.getAttribute("href"));
    expect(hrefs.slice(0, 4)).toEqual(["/build", "/agents", "/workflows", "/playground"]);
    expect(hrefs).toContain("/admin/channels");
    expect(hrefs).toContain("/edge");
    expect(hrefs).toContain("/help");
    cleanup();

    // A member is bounced off /admin/channels, so it must not be offered.
    renderWithReopen({ isAdmin: false });
    fireEvent.click(screen.getByRole("button", { name: "reopenLabel" }));
    const memberHrefs = Array.from(screen.getByTestId("tour-next-panel").querySelectorAll("a")).map((a) =>
      a.getAttribute("href")
    );
    expect(memberHrefs).not.toContain("/admin/channels");
    expect(memberHrefs.slice(0, 4)).toEqual(["/build", "/agents", "/workflows", "/playground"]);
    cleanup();

    // A platform-admin may have no tenant to build in.
    renderWithReopen({ isAdmin: false, isPlatformAdmin: true });
    fireEvent.click(screen.getByRole("button", { name: "reopenLabel" }));
    const platformHrefs = Array.from(screen.getByTestId("tour-next-panel").querySelectorAll("a")).map((a) =>
      a.getAttribute("href")
    );
    expect(platformHrefs).toEqual(["/help"]);
  });

  it("says so, and stays open, when a replay cannot start", () => {
    // Pinned context: replayTour reports false. The old button called it and
    // did nothing visible - indistinguishable from a broken control.
    setTour({ status: "idle", audience: "tenant-admin", replayTour: () => false });
    render(<TourReopenButton />);
    fireEvent.click(screen.getByRole("button", { name: "reopenLabel" }));
    fireEvent.click(screen.getByRole("button", { name: /Replay the product tour/ }));
    expect(screen.getByTestId("tour-next-panel")).toBeInTheDocument();
    expect(screen.getByText(/could not start on this screen/)).toBeInTheDocument();
  });

  it("routes the completion CTAs down the launch path, to everyone but a platform-admin", () => {
    const finishTour = vi.fn();
    setTour({ status: "completed", finishTour });
    render(<TourOverlay />);
    fireEvent.click(screen.getByRole("button", { name: "completedCta" }));
    expect(push).toHaveBeenCalledWith("/build");
    expect(finishTour).toHaveBeenCalled();
    cleanup();

    const finishAgain = vi.fn();
    setTour({ status: "completed", finishTour: finishAgain });
    render(<TourOverlay />);
    fireEvent.click(screen.getByRole("button", { name: CUSTOM_AGENT_CTA }));
    expect(push).toHaveBeenCalledWith("/agents");
    expect(finishAgain).toHaveBeenCalled();
    cleanup();

    setTour({
      status: "completed",
      finishTour: vi.fn(),
      access: {
        audience: "platform-admin",
        plan: null,
        accountType: null,
        experienceMode: null,
        whatsappAllowed: false,
        byokAllowed: false,
        liveVisionAllowed: false,
      },
    });
    render(<TourOverlay />);
    expect(screen.queryByRole("button", { name: CUSTOM_AGENT_CTA })).toBeNull();
    expect(screen.queryByRole("button", { name: "completedCta" })).toBeNull();
  });
});
