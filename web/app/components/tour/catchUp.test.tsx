// Security catch-up for visitors who already decided on the tour.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import { renderTour, walk } from "./tourTestSupport";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen } from "@testing-library/react";

// The "security" step joined the catalogue after most users had finished or
// skipped the tour. A decided visitor is shown THAT step once, as a one-step
// walk through the same overlay, armed exactly like the first-run welcome
// (nav rail + ARM_DELAY_MS, entitlement gate, anchor on screen). Its exits
// record vd_tour_seen_security and never touch vd_tour_status.
describe("security catch-up for visitors who already decided on the tour", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  /** Past ARM_DELAY_MS, so the arming timeout has committed (or declined). */
  function arm() {
    act(() => {
      vi.advanceTimersByTime(1_500);
    });
  }

  it("shows a done member the security step alone, once, and Finish leaves the decision alone", () => {
    window.localStorage.setItem("vd_tour_status", "done");
    renderTour({ isAdmin: false });
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    arm();

    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.getByTestId("ids")).toHaveTextContent(/^security$/);
    expect(screen.getByTestId("total")).toHaveTextContent("1");
    expect(screen.getByTestId("catchup")).toHaveTextContent("true");
    expect(screen.getByRole("heading", { name: "securityTitle" })).toBeInTheDocument();
    // Showing it is not seeing it: only an exit records it.
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "gotIt" }));
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBe("1");
    expect(window.localStorage.getItem("vd_tour_status")).toBe("done");
    expect(window.localStorage.getItem("vd_tour_step")).toBeNull();
    expect(screen.getByTestId("catchup")).toHaveTextContent("false");

    // Next load: never again.
    cleanup();
    renderTour({ isAdmin: false });
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
  });

  it("shows a skipped visitor the step, and dismissing it records it without a reopen note", () => {
    window.localStorage.setItem("vd_tour_status", "skipped");
    renderTour();
    arm();
    expect(screen.getByTestId("ids")).toHaveTextContent(/^security$/);

    fireEvent.click(screen.getByRole("button", { name: "closeLabel" }));
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBe("1");
    expect(window.localStorage.getItem("vd_tour_status")).toBe("skipped");
    expect(window.sessionStorage.getItem("vd_tour_ended")).toBeNull();
    expect(screen.queryByTestId("tour-reopen-hint")).toBeNull();

    cleanup();
    renderTour();
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
  });

  it("is never shown to a platform-admin", () => {
    window.localStorage.setItem("vd_tour_status", "done");
    renderTour({ isAdmin: false, isPlatformAdmin: true, tenantSettings: null });
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBeNull();
  });

  it("is not shown when the tour status is unset - the welcome is, with the full walk behind it", () => {
    renderTour({ isAdmin: false });
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("welcome");
    expect(screen.getByTestId("catchup")).toHaveTextContent("false");
    expect(screen.getByTestId("ids").textContent?.split(",")).toEqual(walk("member"));
  });

  it("does not interrupt a visitor mid-tour: a stored step resumes the full walk", () => {
    window.localStorage.setItem("vd_tour_step", "3");
    renderTour({ isAdmin: false });
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.getByTestId("index")).toHaveTextContent("3");
    expect(screen.getByTestId("catchup")).toHaveTextContent("false");
    expect(screen.getByTestId("ids").textContent?.split(",")).toEqual(walk("member"));
  });

  it("records nothing when the anchor is missing, and shows it on a later load that has it", () => {
    window.localStorage.setItem("vd_tour_status", "done");
    renderTour({ isAdmin: false, omit: ["security"] });
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBeNull();

    cleanup();
    renderTour({ isAdmin: false });
    arm();
    expect(screen.getByTestId("ids")).toHaveTextContent(/^security$/);
  });

  it("finishing the full tour records it, so the catch-up never repeats the step", () => {
    renderTour();
    fireEvent.click(screen.getByTestId("jump-high")); // the last step, past security
    fireEvent.click(screen.getByRole("button", { name: "finish" }));
    expect(screen.getByTestId("status")).toHaveTextContent("completed");
    expect(window.localStorage.getItem("vd_tour_status")).toBe("done");
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBe("1");

    cleanup();
    renderTour();
    arm();
    expect(screen.getByTestId("status")).toHaveTextContent("idle");
  });

  it("a full walk that reaches the security card records it even if skipped afterwards", () => {
    renderTour({ isAdmin: false });
    fireEvent.click(screen.getByTestId("start"));
    const at = walk("member").indexOf("security");
    for (let i = 0; i < at; i += 1) fireEvent.click(screen.getByRole("button", { name: "next" }));
    expect(screen.getByRole("heading", { name: "securityTitle" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "skip" }));
    expect(window.localStorage.getItem("vd_tour_status")).toBe("skipped");
    expect(window.localStorage.getItem("vd_tour_seen_security")).toBe("1");
  });

  it("a replay during the catch-up becomes the full tour, and is no longer a catch-up", () => {
    window.localStorage.setItem("vd_tour_status", "done");
    renderTour({ isAdmin: false });
    arm();
    expect(screen.getByTestId("catchup")).toHaveTextContent("true");

    fireEvent.click(screen.getByTestId("replay"));
    expect(screen.getByTestId("catchup")).toHaveTextContent("false");
    expect(screen.getByTestId("status")).toHaveTextContent("active");
    expect(screen.getByTestId("index")).toHaveTextContent("0");
    expect(screen.getByTestId("ids").textContent?.split(",")).toEqual(walk("member"));
  });
});
