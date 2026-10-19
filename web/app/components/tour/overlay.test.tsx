// TourOverlay rendering: status branches, counters, Show me, naming, keyboard.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import { TOUR_MESSAGES, activeContext, mock, push, setTour } from "./tourTestSupport";
import { describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import TourOverlay from "./TourOverlay";

describe("TourOverlay status branches", () => {
  it("renders the welcome hero, and only it, when status is welcome", () => {
    setTour({ status: "welcome", dismissWelcome: vi.fn(), startTour: vi.fn() });
    render(<TourOverlay />);

    expect(screen.getByRole("button", { name: "welcomePrimary" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "welcomeSkip" })).toBeInTheDocument();
    expect(screen.getByText("duration")).toBeInTheDocument();
    // Not the step card, and not the completion screen.
    expect(screen.queryByText("stepCounter")).toBeNull();
    expect(screen.queryByText("completedTitle")).toBeNull();
  });

  it("renders the step card when status is active", () => {
    setTour(activeContext("agents"));
    render(<TourOverlay />);

    expect(screen.getByRole("heading", { name: "agentsTitle" })).toBeInTheDocument();
    expect(screen.getByText("agentsBody")).toBeInTheDocument();
    expect(screen.getByText("agentsPoint1")).toBeInTheDocument();
    expect(screen.getByText("agentsPoint2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "next" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "skip" })).toBeInTheDocument();
  });

  it("offers Finish rather than Next on the last step of the SELECTED list", () => {
    // `isLast` is stepIndex + 1 === totalSteps, and totalSteps is the selected
    // list. A member on their sixth of six must see Finish even though the
    // catalogue has twelve entries.
    setTour(activeContext("help", { index: 5, total: 6 }));
    render(<TourOverlay />);

    expect(screen.getByRole("button", { name: "finish" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "next" })).toBeNull();
  });

  it("renders the completion screen when status is completed", () => {
    setTour({ status: "completed", finishTour: vi.fn() });
    render(<TourOverlay />);

    expect(screen.getByRole("heading", { name: "completedTitle" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "completedCta" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "completedSecondary" })).toBeInTheDocument();
    expect(screen.getByText("replayHint")).toBeInTheDocument();
  });

  it('offers a platform-admin no "create an agent" button on the way out', () => {
    // They may have no tenant to create one in. The dismiss control is
    // promoted rather than the CTA merely hidden, so the modal still has a
    // primary action.
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

    expect(screen.queryByRole("button", { name: "completedCta" })).toBeNull();
    expect(screen.getByRole("button", { name: "completedSecondary" })).toBeInTheDocument();
  });

  it("renders nothing at all while idle", () => {
    setTour({ status: "idle", currentStep: null });
    const { container } = render(<TourOverlay />);
    expect(container).toBeEmptyDOMElement();
    expect(document.body.querySelector('[role="dialog"]')).toBeNull();
  });

  it("dims the page when a step has no spotlight to fall back on", () => {
    // D7/D9. With no rect there is no spotlight SVG, and there used to be no
    // dimmer either - the card declared role="dialog" aria-modal="true" and
    // ran a Tab trap over a page that was fully bright and fully tappable.
    // That is the normal state on a phone (the rail is -translate-x-full
    // below lg) and on step 0, which has no target by design. Nothing in this
    // tree carries a data-tour attribute, so this is the no-rect branch.
    setTour(activeContext("start", { index: 0 }));
    render(<TourOverlay />);

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(
      document.body.querySelector('[class*="bg-black/55"]'),
      "no scrim behind an aria-modal card that has no spotlight"
    ).not.toBeNull();
  });
});

describe("TourOverlay counts the selected list", () => {
  it('says "Step 2 of 6" to a member on their second of six', () => {
    // Real copy for this one: the claim is about the NUMBERS, and the numbers
    // only exist once the placeholders are filled. Six is a member's SIMPLE
    // walk; twelve is the catalogue and must never appear.
    mock.realCopy = true;
    setTour(activeContext("agents", { index: 1, total: 6 }));
    render(<TourOverlay />);

    expect(screen.getByText("Step 2 of 6")).toBeInTheDocument();
    expect(screen.queryByText(/of 12/)).toBeNull();
    expect(screen.queryByText(/of 9/)).toBeNull();
    // The rail draws one segment per SELECTED step, not per catalogue entry.
    expect(screen.getAllByRole("button", { name: /^Go to step/ })).toHaveLength(6);
    expect(screen.getByRole("navigation", { name: "2 / 6" })).toBeInTheDocument();
  });

  it("promises a minute, not two, when the walk is a short one", () => {
    // A three-step platform walk described as "About 2 minutes" is the same
    // class of countable lie as step 0's old "Eight short stops".
    mock.realCopy = true;
    setTour({ status: "welcome", dismissWelcome: vi.fn(), startTour: vi.fn(), totalSteps: 3 });
    render(<TourOverlay />);
    expect(screen.getByText("About a minute")).toBeInTheDocument();
    cleanup();

    setTour({ status: "welcome", dismissWelcome: vi.fn(), startTour: vi.fn(), totalSteps: 9 });
    render(<TourOverlay />);
    expect(screen.getByText("About 2 minutes")).toBeInTheDocument();
  });

  it("tells a platform-admin what the tour is about, not that they will build an agent", () => {
    mock.realCopy = true;
    setTour({
      status: "welcome",
      dismissWelcome: vi.fn(),
      startTour: vi.fn(),
      totalSteps: 3,
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

    expect(screen.getByText(String(TOUR_MESSAGES.welcomeBodyPlatform))).toBeInTheDocument();
    expect(screen.queryByText(String(TOUR_MESSAGES.welcomeBody))).toBeNull();
  });
});

describe('TourOverlay "Show me"', () => {
  it("navigates and leaves you on the step you asked to see", () => {
    // It used to call router.push(step.href) AND tour.nextStep() in the same
    // tick, so asking to be shown Agents landed you on /agents while the card
    // had already repainted to "Workflows put steps in order" and the
    // spotlight had jumped away: the one control whose whole promise is "look
    // at this" was the one control that guaranteed you could not. The target
    // poll re-finds the element on the new route, so staying put is what
    // makes the light land on it.
    const ctx = activeContext("agents"); // href: "/agents"
    setTour(ctx);
    render(<TourOverlay />);

    fireEvent.click(screen.getByRole("button", { name: "showMe" }));

    expect(push).toHaveBeenCalledWith("/agents");
    expect(ctx.nextStep, '"Show me" advanced the step it was asked to show').not.toHaveBeenCalled();
    expect(ctx.goToStep).not.toHaveBeenCalled();
    // Still on the agents copy, not the next step's.
    expect(screen.getByRole("heading", { name: "agentsTitle" })).toBeInTheDocument();
  });

  it("is absent on a step that has no route to show", () => {
    // A "Show me" with nowhere to go would be a dead button; step 0 is the
    // centred opener and targets nothing.
    setTour(activeContext("start", { index: 0 }));
    render(<TourOverlay />);
    expect(screen.queryByRole("button", { name: "showMe" })).toBeNull();
    expect(push).not.toHaveBeenCalled();
  });
});

describe("TourOverlay accessible naming", () => {
  it("names the dialog once, and does not announce the position twice", () => {
    // The dialog NODE survives every step change (useDialogFocus refocuses it
    // by id), so its accessible name has to be the stable `dialogLabel` and
    // not the current step's title - both `dialogLabel` and `progress` were
    // sitting unused in en.json while the progress rail carried a duplicate
    // of the aria-live step counter.
    setTour(activeContext("workflows", { index: 2 }));
    render(<TourOverlay />);

    expect(screen.getByRole("dialog", { name: "dialogLabel" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "progress" })).toBeInTheDocument();
    // The counter sentence belongs to the live region alone.
    expect(screen.queryByRole("navigation", { name: "stepCounter" })).toBeNull();
    expect(screen.getByText("stepCounter")).toHaveAttribute("aria-live", "polite");
  });
});

describe("TourOverlay keyboard control", () => {
  it("ArrowRight advances, ArrowLeft goes back, Escape skips", () => {
    const ctx = activeContext("knowledge", { index: 3 });
    setTour(ctx);
    render(<TourOverlay />);

    fireEvent.keyDown(window, { key: "ArrowRight" });
    expect(ctx.nextStep).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: "ArrowLeft" });
    expect(ctx.prevStep).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(window, { key: "Escape" });
    expect(ctx.skipTour).toHaveBeenCalledTimes(1);
    // Escape must not also advance - a skip that moves a step first would
    // write the wrong resume index on its way out.
    expect(ctx.nextStep).toHaveBeenCalledTimes(1);
  });

  it("leaves Enter to the browser when focus is already on a control", () => {
    const ctx = activeContext("workflows", { index: 2 });
    setTour(ctx);
    render(<TourOverlay />);

    // Enter on the Next button: the button's own click will fire, so the
    // global handler must not advance a second time.
    fireEvent.keyDown(screen.getByRole("button", { name: "next" }), { key: "Enter" });
    expect(ctx.nextStep).not.toHaveBeenCalled();
  });
});
