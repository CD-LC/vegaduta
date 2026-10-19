import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

// A plain function, not vi.fn(): vitest's spy bookkeeping flags a tracked
// promise that rejects as an unhandled rejection even when the caller catches it.
const calls: unknown[] = [];
let behaviour: () => Promise<unknown> = () => Promise.resolve({});
vi.mock("../../lib/api", () => ({
  api: {
    createAdminUser: (payload: unknown) => {
      calls.push(payload);
      return behaviour();
    },
  },
}));

import AdminWelcomeBody from "./AdminWelcomeBody";

const setup = () => {
  const props = { onStart: vi.fn(), onBuild: vi.fn(), onSkip: vi.fn() };
  render(<AdminWelcomeBody titleId="t" bodyId="b" {...props} />);
  return props;
};

describe("AdminWelcomeBody", () => {
  beforeEach(() => {
    calls.length = 0;
    behaviour = () => Promise.resolve({});
  });

  it("tells the agent-first story and offers all three exits", () => {
    const p = setup();
    expect(screen.getByText(/Admin controls unlocked/)).toBeInTheDocument();
    expect(screen.getByText(/Build the agent/)).toBeInTheDocument();
    // The first-login story itself: agent -> workflow -> app, not generic copy.
    for (const concept of [/custom AI agent/i, /workflow/i, /app/i]) {
      expect(screen.getAllByText(concept).length).toBeGreaterThan(0);
    }
    fireEvent.click(screen.getByRole("button", { name: /Show me the launch path/ }));
    fireEvent.click(screen.getByRole("button", { name: /Build my agent/ }));
    fireEvent.click(screen.getByRole("button", { name: /Skip for now/ }));
    expect(p.onStart).toHaveBeenCalledOnce();
    expect(p.onBuild).toHaveBeenCalledOnce();
    expect(p.onSkip).toHaveBeenCalledOnce();
  });

  it("invites by email through the real admin endpoint, never with a password", async () => {
    setup();
    fireEvent.click(screen.getByRole("button", { name: /Invite a teammate/ }));
    const send = screen.getByRole("button", { name: /Send invitation/ });
    expect(send).toBeDisabled();
    fireEvent.change(screen.getByLabelText("First name"), { target: { value: "Asha" } });
    fireEvent.change(screen.getByLabelText("Last name"), { target: { value: "Rao" } });
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "Asha@Example.com" } });
    expect(send).toBeEnabled();
    fireEvent.click(send);
    await waitFor(() => expect(screen.getByText(/Invited: asha@example.com/)).toBeInTheDocument());
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({ email: "asha@example.com", username: "asha@example.com", inviteByEmail: true, temporaryPassword: null });
  });

  it("shows the server's message when the invite is refused", async () => {
    behaviour = () => Promise.reject(new Error("User limit reached for your plan"));
    setup();
    fireEvent.click(screen.getByRole("button", { name: /Invite a teammate/ }));
    fireEvent.change(screen.getByLabelText("First name"), { target: { value: "A" } });
    fireEvent.change(screen.getByLabelText("Last name"), { target: { value: "B" } });
    fireEvent.change(screen.getByLabelText("Email address"), { target: { value: "a@b.co" } });
    fireEvent.click(screen.getByRole("button", { name: /Send invitation/ }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("User limit reached for your plan"));
  });
});
