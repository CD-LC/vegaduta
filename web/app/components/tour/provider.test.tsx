// The real provider wired to the overlay, and first-run modal succession.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import { ADMIN_WALK, headingOf, push, renderTour, setTour, walk } from "./tourTestSupport";
import { describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { TOUR_STEPS } from "../TourProvider";
import AgentsGuide from "../agents/AgentsGuide";

describe("tour integration (real provider)", () => {
  it("hands a tenant-admin the admin walk and a member their own", () => {
    renderTour();
    fireEvent.click(screen.getByTestId("start"));
    expect(screen.getByTestId("audience")).toHaveTextContent("tenant-admin");
    expect(screen.getByTestId("ids")).toHaveTextContent(ADMIN_WALK.join(","));
    expect(screen.getByTestId("total")).toHaveTextContent(String(ADMIN_WALK.length));
    cleanup();

    renderTour({ isAdmin: false });
    fireEvent.click(screen.getByTestId("start"));
    expect(screen.getByTestId("audience")).toHaveTextContent("member");
    expect(screen.getByTestId("ids")).toHaveTextContent(walk("member").join(","));
  });

  it("gives a platform-admin with no tenant a six-step tour, not a blank one", () => {
    // tenantSettings null is their steady state, not a loading glitch. The
    // provider must still hand them a walk.
    renderTour({ isAdmin: false, isPlatformAdmin: true, tenantSettings: null });
    fireEvent.click(screen.getByTestId("start"));

    expect(screen.getByTestId("audience")).toHaveTextContent("platform-admin");
    expect(screen.getByTestId("ids")).toHaveTextContent(
      "platform-console,platform-workspace,topbar,search,account,help"
    );
    expect(screen.getByTestId("status")).toHaveTextContent("active");
  });

  it("drops the step whose nav row this visitor's shell does not paint", () => {
    // The reachability guarantee, end to end: the marker is simply not in the
    // document, and the step is gone from the walk rather than spotlighting
    // nothing.
    renderTour({ omit: ["nav-monitor"] });
    fireEvent.click(screen.getByTestId("start"));

    const ids_ = screen.getByTestId("ids").textContent ?? "";
    expect(ids_.split(",")).not.toContain("monitor");
    expect(ids_.split(",")).toContain("agents");
    expect(document.querySelector('[data-tour="nav-monitor"]')).toBeNull();
  });

  it("still renders an advanceable card when a step's target leaves the DOM mid-tour", () => {
    // v1 rendered nothing in that case and left the user with an invisible,
    // un-advanceable tour. Rule 4 judges reachability ONCE, when the tour
    // starts, so a marker that disappears afterwards (a route change, a
    // collapsing rail) must not blank the card it already committed to.
    renderTour();
    fireEvent.click(screen.getByTestId("start"));
    const agentsAt = ADMIN_WALK.indexOf("agents");
    for (let i = 0; i < agentsAt; i += 1) fireEvent.click(screen.getByRole("button", { name: "next" })); // -> agents

    screen.getByTestId("anchor-nav-agents").remove();
    expect(document.querySelector('[data-tour="nav-agents"]')).toBeNull();

    expect(screen.getByRole("heading", { name: "agentsTitle" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    expect(screen.getByTestId("index")).toHaveTextContent(String(agentsAt + 1));
  });

  it("walks forward and back through every selected step without leaving the card", () => {
    renderTour();
    fireEvent.click(screen.getByTestId("start"));
    expect(screen.getByRole("heading", { name: "startTitle" })).toBeInTheDocument();

    for (let i = 1; i < ADMIN_WALK.length; i += 1) {
      fireEvent.click(screen.getByRole("button", { name: "next" }));
      expect(screen.getByTestId("index")).toHaveTextContent(String(i));
      expect(screen.getByRole("heading", { name: headingOf(ADMIN_WALK[i]) })).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("button", { name: "back" }));
    expect(screen.getByTestId("index")).toHaveTextContent(String(ADMIN_WALK.length - 2));

    // Finish on the last step hands over to the completion screen.
    fireEvent.click(screen.getByRole("button", { name: "next" }));
    fireEvent.click(screen.getByRole("button", { name: "finish" }));
    expect(screen.getByTestId("status")).toHaveTextContent("completed");
    expect(screen.getByRole("heading", { name: "completedTitle" })).toBeInTheDocument();
  });

  it("goToStep jumps to the requested index and clamps against the SELECTED list", () => {
    // The clamp bound is the walk's length, never TOUR_STEPS.length: a stored
    // index written by a tenant-admin mid-tour, read back by the same browser
    // as a member, used to resume onto `undefined` and render a blank card.
    renderTour();

    fireEvent.click(screen.getByTestId("jump-3"));
    expect(screen.getByTestId("index")).toHaveTextContent("3");
    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.getByRole("heading", { name: headingOf(ADMIN_WALK[3]) })).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("jump-high"));
    expect(screen.getByTestId("index")).toHaveTextContent(String(ADMIN_WALK.length - 1));
    expect(Number(screen.getByTestId("index").textContent)).toBeLessThan(TOUR_STEPS.length - 1);

    fireEvent.click(screen.getByTestId("jump-low"));
    expect(screen.getByTestId("index")).toHaveTextContent("0");

    fireEvent.click(screen.getByTestId("jump-high"));
    fireEvent.click(screen.getByTestId("jump-nan"));
    expect(screen.getByTestId("index")).toHaveTextContent("0");
  });

  it("the progress rail has one segment per selected step, and skip ends the tour", () => {
    renderTour();
    fireEvent.click(screen.getByTestId("start"));

    // Each a real button - v1's dots were bare <span>s that keyboard and
    // assistive tech could not reach at all.
    const segments = screen.getAllByRole("button", { name: "jumpToStep" });
    expect(segments).toHaveLength(ADMIN_WALK.length);
    fireEvent.click(segments[5]);
    expect(screen.getByTestId("index")).toHaveTextContent("5");

    fireEvent.click(screen.getByRole("button", { name: "skip" }));
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(screen.queryByRole("dialog")).toBeNull();
    // Skipping is a decision, and it must survive a reload.
    expect(window.localStorage.getItem("vd_tour_status")).toBe("skipped");
    expect(window.localStorage.getItem("vd_tour_step")).toBeNull();
  });

  it("gives a member a shorter rail than an admin, from the same catalogue", () => {
    renderTour({ isAdmin: false });
    fireEvent.click(screen.getByTestId("start"));
    const memberWalk = walk("member");
    expect(screen.getAllByRole("button", { name: "jumpToStep" })).toHaveLength(memberWalk.length);
    expect(memberWalk.length).toBeLessThan(ADMIN_WALK.length);
  });

  it('"Show me" does not move the real provider\'s step index either', () => {
    // The mocked-context version of this asserts nextStep was not called; this
    // one asserts the observable consequence against the provider that owns
    // the index, so a future "navigate then advance" reintroduced anywhere
    // else in the chain still fails.
    renderTour();
    fireEvent.click(screen.getByTestId("start"));
    const agentsAt = ADMIN_WALK.indexOf("agents");
    for (let i = 0; i < agentsAt; i += 1) fireEvent.click(screen.getByRole("button", { name: "next" })); // -> "agents"
    expect(screen.getByTestId("index")).toHaveTextContent(String(agentsAt));

    fireEvent.click(screen.getByRole("button", { name: "showMe" }));

    expect(push).toHaveBeenCalledWith("/agents");
    expect(screen.getByTestId("index")).toHaveTextContent(String(agentsAt));
    expect(screen.getByRole("heading", { name: "agentsTitle" })).toBeInTheDocument();
  });

  it("marks the session as ended on both exits, but not on dismissing the hero", () => {
    // vd_tour_ended is what stops the NEXT first-run modal (see the succession
    // suite below). Skip has to set it too: someone who dismissed the tour at
    // step 3 is at least as unimpressed by AgentsGuide ten seconds later as
    // someone who finished. Dismissing the welcome hero must NOT set it - the
    // tour never ran, so there is nothing for a later modal to be redundant
    // with, and suppressing the guide there would cost a real first-run
    // explanation for free.
    // Each leg is a fresh browser: unmount and clear, or the previous
    // harness stays in document.body and every query finds two of everything.
    const freshVisit = () => {
      cleanup();
      window.sessionStorage.clear();
      window.localStorage.clear();
      renderTour();
    };

    renderTour();
    fireEvent.click(screen.getByTestId("start"));
    fireEvent.click(screen.getByRole("button", { name: "skip" }));
    expect(window.sessionStorage.getItem("vd_tour_ended")).toBe("1");

    freshVisit();
    fireEvent.click(screen.getByTestId("jump-high")); // clamps to the last step
    fireEvent.click(screen.getByRole("button", { name: "finish" }));
    expect(screen.getByTestId("status")).toHaveTextContent("completed");
    // The completion screen's own close finishes the tour for real.
    fireEvent.click(screen.getByRole("button", { name: "completedSecondary" }));
    expect(window.sessionStorage.getItem("vd_tour_ended")).toBe("1");

    // Dismissing the hero records the DECISION but not the session flag.
    freshVisit();
    fireEvent.click(screen.getByTestId("dismiss"));
    expect(window.localStorage.getItem("vd_tour_status")).toBe("skipped");
    expect(
      window.sessionStorage.getItem("vd_tour_ended"),
      "dismissing the hero suppressed the next first-run modal for a tour that never ran"
    ).toBeNull();
  });
});

// Overlap was already guarded; SUCCESSION was not. TourOverlay's completion
// CTA calls router.push("/agents") and THEN finishTour(), so by the time
// AgentsGuide mounts on /agents for the first time vd_tour_status is written,
// isTourPending() is false and status is back to "idle" - every existing
// guard passed and the six-step "How agents work" modal opened on the frame
// after the tour that had just spent step 1 explaining agents. A new
// visitor's real sequence was seven surfaces deep.
describe("first-run modal succession", () => {
  /** The state AgentsGuide finds itself in right after a completed tour:
   * a decision recorded, no guide-seen flag, tour context back to idle. */
  function afterTheTour() {
    window.localStorage.setItem("vd_tour_status", "done");
    window.sessionStorage.setItem("vd_tour_ended", "1");
  }

  it("AgentsGuide does not auto-open in the same session the tour ended", () => {
    afterTheTour();
    render(<AgentsGuide />);

    expect(screen.queryByRole("dialog")).toBeNull();
    // Suppressed, never spent: the guide's one first-run showing must still be
    // waiting in a later session, so the seen flag must NOT have been written.
    expect(window.localStorage.getItem("vd_agents_guide_seen_v1")).toBeNull();
    // The manual trigger is untouched - this suppresses an auto-open, not the
    // guide itself.
    expect(screen.getByRole("button", { name: /triggerLabel/ })).toBeInTheDocument();
  });

  it("and does auto-open once the session flag is gone", () => {
    // The control case. Without it the test above would still pass if the
    // guard were `return` on every path, or if the modal had been deleted.
    window.localStorage.setItem("vd_tour_status", "done");
    window.sessionStorage.clear();
    render(<AgentsGuide />);

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "title" })).toBeInTheDocument();
  });

  it("marks the guide seen at auto-open - a navigate-away cannot re-fire it", () => {
    // Repeat-popup fix (2026-09-23): SEEN_KEY is written when the modal
    // OPENS, not when it closes. The old write-on-close contract let a
    // navigation (the common exit - the modal is a portal over the whole
    // app) unmount the guide without marking it, so it auto-opened on
    // every /agents visit.
    window.localStorage.setItem("vd_tour_status", "done");
    render(<AgentsGuide />);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    // Flag already set with the modal still open - no close() ever ran.
    expect(window.localStorage.getItem("vd_agents_guide_seen_v1")).toBe("1");

    // Unmount (what a navigation does) and mount again: must not re-open.
    cleanup();
    render(<AgentsGuide />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("and does not auto-open while the tour is still pending or running", () => {
    // The original overlap guard, kept: no decision recorded yet means the
    // welcome modal is still armed on its 900ms timer.
    render(<AgentsGuide />);
    expect(screen.queryByRole("dialog")).toBeNull();
    cleanup();

    // "completed" is the state while the tour's own completion card is on
    // screen. It was missing from the guard list, and was harmless only
    // because the effect is mount-only - a trap for whoever first gives that
    // effect a dependency array.
    window.localStorage.setItem("vd_tour_status", "done");
    setTour({ status: "completed" });
    render(<AgentsGuide />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
