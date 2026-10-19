// The plan panel's behaviour, not its copy: which sections appear for which
// response shape, that steps focus their node, that requirement links stay
// inside the console, and that the schedule can only be switched on by an
// admin on an ACTIVE workflow.

import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// t() returns the key, so assertions bind to keys rather than English copy.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import WorkflowPlanPanel, { type WorkflowPlanPanelProps } from "./WorkflowPlanPanel";
import { normalizePlan } from "./workflowPlan";

const FULL = normalizePlan({
  notes: ["Picked the first knowledge collection."],
  mode: "create",
  explanation: [
    { nodeId: "t1", label: "Start", type: "trigger", sentence: "Starts on a schedule." },
    { nodeId: "gone", label: "Old", type: "agent", sentence: "Finds unpaid invoices." },
    { nodeId: "o1", label: "Send", type: "output", sentence: "Sends a WhatsApp reminder." },
  ],
  suggestedSchedule: { cron: "0 9 * * 1", label: "Every Monday 09:00", timezone: "UTC" },
  requirements: {
    items: [
      { kind: "channel", message: "Connect WhatsApp.", href: "/admin/channels", nodeId: "o1" },
      { kind: "secret", message: "Add a key.", href: "https://evil.example", nodeId: null },
    ],
  },
  insertedApprovals: [{ approvalNodeId: "auto-approval-o1", beforeNodeId: "o1", sendNodeId: "o1" }],
});

function renderPanel(overrides: Partial<WorkflowPlanPanelProps> = {}) {
  const props: WorkflowPlanPanelProps = {
    plan: FULL,
    onFocusNode: vi.fn(),
    nodeExists: (id) => id !== "gone",
    onClose: vi.fn(),
    workflowActive: false,
    canManageSchedule: true,
    onTurnOnSchedule: vi.fn(),
    onTestRun: vi.fn(),
    ...overrides,
  };
  render(<WorkflowPlanPanel {...props} />);
  return props;
}

describe("WorkflowPlanPanel", () => {
  it("lists the steps in order and focuses a step's node on click", () => {
    const props = renderPanel();
    const steps = screen.getAllByTestId("workflow-plan-step");
    expect(steps).toHaveLength(3);
    expect(steps[0].textContent).toContain("Starts on a schedule.");
    fireEvent.click(steps[2]);
    expect(props.onFocusNode).toHaveBeenCalledWith("o1");
  });

  it("does not make a step clickable once its node is gone", () => {
    const props = renderPanel();
    const stale = screen.getAllByTestId("workflow-plan-step")[1];
    expect(stale.tagName).not.toBe("BUTTON");
    fireEvent.click(stale);
    expect(props.onFocusNode).not.toHaveBeenCalled();
  });

  it("links a requirement only to a console path", () => {
    renderPanel();
    const links = screen.getAllByTestId("workflow-plan-requirement-link");
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute("href")).toBe("/admin/channels");
    expect(screen.getByTestId("workflow-plan-requirements").textContent).toContain("Add a key.");
  });

  it("points at the approval the server added", () => {
    const props = renderPanel();
    fireEvent.click(screen.getByTestId("workflow-plan-approval"));
    expect(props.onFocusNode).toHaveBeenCalledWith("auto-approval-o1");
  });

  it("says the schedule waits for activation while the workflow is a draft", () => {
    renderPanel({ workflowActive: false });
    expect(screen.queryByTestId("workflow-plan-schedule-on")).toBeNull();
    expect(screen.getByTestId("workflow-plan-schedule").textContent).toContain("planScheduleAfterActivate");
  });

  it("lets an admin switch the schedule on once ACTIVE", () => {
    const props = renderPanel({ workflowActive: true });
    fireEvent.click(screen.getByTestId("workflow-plan-schedule-on"));
    expect(props.onTurnOnSchedule).toHaveBeenCalledTimes(1);
  });

  it("offers no schedule button to a non-admin, or when it is already on", () => {
    renderPanel({ workflowActive: true, canManageSchedule: false });
    expect(screen.queryByTestId("workflow-plan-schedule-on")).toBeNull();
    expect(screen.getByTestId("workflow-plan-schedule").textContent).toContain("planScheduleAdminOnly");
  });

  it("shows the schedule as on instead of a button", () => {
    renderPanel({ workflowActive: true, scheduleOn: true });
    expect(screen.queryByTestId("workflow-plan-schedule-on")).toBeNull();
    expect(screen.getByTestId("workflow-plan-schedule").textContent).toContain("planScheduleOn");
  });

  it("starts a test run, and hides the button when the caller cannot run drafts", () => {
    const props = renderPanel();
    fireEvent.click(screen.getByTestId("workflow-plan-test-run"));
    expect(props.onTestRun).toHaveBeenCalledTimes(1);
  });

  it("hides the test run button without a handler", () => {
    renderPanel({ onTestRun: undefined });
    expect(screen.queryByTestId("workflow-plan-test-run")).toBeNull();
  });

  it("reports the last dry run only when there is one", () => {
    renderPanel({ dryRunSends: 0 });
    expect(screen.getByTestId("workflow-plan-dry-run")).toBeTruthy();
  });

  it("renders only notes against an older core", () => {
    renderPanel({ plan: normalizePlan({ notes: ["No agents exist yet."] }) });
    expect(screen.getByTestId("workflow-plan-notes").textContent).toContain("No agents exist yet.");
    for (const id of ["workflow-plan-steps", "workflow-plan-requirements", "workflow-plan-approvals", "workflow-plan-schedule", "workflow-plan-dry-run"]) {
      expect(screen.queryByTestId(id)).toBeNull();
    }
  });

  it("closes", () => {
    const props = renderPanel();
    fireEvent.click(screen.getByTestId("workflow-plan-close"));
    expect(props.onClose).toHaveBeenCalledTimes(1);
  });
});
