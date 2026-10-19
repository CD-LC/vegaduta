// The composer's contract with the canvas, not its copy: what it sends to
// POST /api/workflows, that the sentence travels in sessionStorage (never the
// URL), and that a failed create neither navigates nor leaves a stale handoff.

import type React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const createWorkflow = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get createWorkflowQuietOnCap() { return createWorkflow; },
  },
}));

// The real UpgradeLock is a Radix tooltip that needs a provider; its contract here is "wraps the message".
vi.mock("../ui/upgrade-lock", () => ({
  UpgradeLock: ({ children, label }: { children: React.ReactNode; label?: string }) => (
    <span data-testid="upgrade-lock" data-label={label}>
      {children}
    </span>
  ),
}));

// t() returns the key, so assertions bind to keys rather than English copy.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

import DescribeComposer from "./DescribeComposer";
import { DESCRIBE_HANDOFF_PREFIX } from "./describeHandoff";

beforeEach(() => {
  createWorkflow.mockReset();
  window.sessionStorage.clear();
});

function type(value: string) {
  fireEvent.change(screen.getByTestId("workflow-describe-input"), { target: { value } });
}

describe("DescribeComposer", () => {
  it("keeps the button disabled until something is typed", () => {
    render(<DescribeComposer onCreated={() => {}} />);
    const submit = screen.getByTestId("workflow-describe-submit") as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    type("   ");
    expect(submit.disabled).toBe(true);
    type("send a weekly digest");
    expect(submit.disabled).toBe(false);
  });

  it("creates a draft with a derived name, stashes the prompt and hands back the id", async () => {
    createWorkflow.mockResolvedValue({ id: "wf-9" });
    const onCreated = vi.fn();
    render(<DescribeComposer onCreated={onCreated} />);

    type("every monday pull unpaid invoices and whatsapp   a reminder");
    fireEvent.click(screen.getByTestId("workflow-describe-submit"));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith("wf-9"));
    expect(createWorkflow).toHaveBeenCalledTimes(1);
    const payload = createWorkflow.mock.calls[0][0];
    expect(payload.name).toBe("Every monday pull unpaid invoices and");
    expect(payload.description).toBe("every monday pull unpaid invoices and whatsapp a reminder");
    expect(payload.graph.nodes).toHaveLength(1);
    expect(payload.graph.nodes[0].type).toBe("trigger");
    expect(payload.graph.edges).toEqual([]);
    expect(window.sessionStorage.getItem(`${DESCRIBE_HANDOFF_PREFIX}wf-9`)).toBe(
      "every monday pull unpaid invoices and whatsapp a reminder",
    );
  });

  it("fills the textarea from an example chip without creating anything", () => {
    render(<DescribeComposer onCreated={() => {}} />);
    fireEvent.click(screen.getByTestId("workflow-describe-example-exampleInvoice"));
    expect((screen.getByTestId("workflow-describe-input") as HTMLTextAreaElement).value).toBe("exampleInvoicePrompt");
    expect(createWorkflow).not.toHaveBeenCalled();
  });

  it("shows the server message, does not navigate and stores nothing when create fails", async () => {
    createWorkflow.mockRejectedValue(new Error("This plan allows up to 3 workflows"));
    const onCreated = vi.fn();
    render(<DescribeComposer onCreated={onCreated} />);

    type("triage support messages");
    fireEvent.click(screen.getByTestId("workflow-describe-submit"));

    const alert = await screen.findByTestId("workflow-describe-error");
    expect(alert.textContent).toBe("This plan allows up to 3 workflows");
    expect(onCreated).not.toHaveBeenCalled();
    expect(window.sessionStorage.length).toBe(0);
    // The button comes back so the person can retry.
    expect((screen.getByTestId("workflow-describe-submit") as HTMLButtonElement).disabled).toBe(false);
  });

  it("shows the plan cap (403) inline with the upgrade affordance", async () => {
    createWorkflow.mockRejectedValue(Object.assign(new Error("This plan (FREE) allows up to 3 workflows"), { status: 403 }));
    render(<DescribeComposer onCreated={() => {}} />);

    type("triage support messages");
    fireEvent.click(screen.getByTestId("workflow-describe-submit"));

    const alert = await screen.findByTestId("workflow-describe-error");
    expect(alert.textContent).toBe("This plan (FREE) allows up to 3 workflows");
    expect(alert.getAttribute("data-cap-hit")).toBe("true");
    expect(screen.getByTestId("upgrade-lock").getAttribute("data-label")).toBe("upgradeYourPlan");
  });

  it("does not create twice on a double submit", async () => {
    let resolve: (v: { id: string }) => void = () => {};
    createWorkflow.mockReturnValue(new Promise((r) => { resolve = r; }));
    const onCreated = vi.fn();
    render(<DescribeComposer onCreated={onCreated} />);

    type("weekly digest");
    const form = screen.getByTestId("workflow-describe-submit").closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    resolve({ id: "wf-1" });

    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    expect(createWorkflow).toHaveBeenCalledTimes(1);
  });
});
