import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const listSessionTurns = vi.hoisted(() => vi.fn());
vi.mock("../../lib/api/turns", () => ({ listSessionTurns }));
vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useLocale: () => "en",
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import SessionTurns from "./SessionTurns";

const AGENT = "11111111-1111-4111-8111-111111111111";
const T1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const T2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

const turns = [
  { turnId: T2, agentId: AGENT, startedAt: "2026-10-15T10:05:00Z", lastEventAt: "2026-10-15T10:05:02Z", events: 7, status: "error:Timeout" },
  { turnId: T1, agentId: AGENT, startedAt: "2026-10-15T10:00:00Z", lastEventAt: "2026-10-15T10:00:01Z", events: 4, status: "completed" },
];

function fill() {
  fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: AGENT } });
  fireEvent.change(screen.getByLabelText("Session ID"), { target: { value: "sess-1" } });
  fireEvent.click(screen.getByRole("button", { name: /find turns/i }));
}

describe("SessionTurns", () => {
  beforeEach(() => listSessionTurns.mockReset());

  it("rejects a missing or malformed id without calling the API", () => {
    render(<SessionTurns onSelect={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: /find turns/i }));
    expect(screen.getByRole("alert")).toHaveTextContent(/Enter the agent ID/);
    expect(listSessionTurns).not.toHaveBeenCalled();
  });

  it("lists the session's turns newest first and opens the newest one", async () => {
    listSessionTurns.mockResolvedValue({ agentId: AGENT, sessionId: "sess-1", turns });
    const onSelect = vi.fn();
    render(<SessionTurns onSelect={onSelect} />);
    fill();
    await screen.findByLabelText("2 journaled turns");
    expect(listSessionTurns).toHaveBeenCalledWith(AGENT, "sess-1");
    const rows = screen.getAllByTestId("session-turn");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("error:Timeout");
    expect(rows[0]).toHaveTextContent("7 events");
    expect(rows[1]).toHaveTextContent("completed");
    expect(onSelect).toHaveBeenCalledWith(AGENT, T2);
  });

  it("opens the turn the user picks and marks it", async () => {
    listSessionTurns.mockResolvedValue({ agentId: AGENT, sessionId: "sess-1", turns });
    const onSelect = vi.fn();
    const { rerender } = render(<SessionTurns onSelect={onSelect} selectedTurnId={T2} />);
    fill();
    await screen.findByLabelText("2 journaled turns");
    const buttons = screen.getAllByRole("button", { name: /open timeline|shown below/i });
    expect(buttons[0]).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(buttons[1]);
    expect(onSelect).toHaveBeenLastCalledWith(AGENT, T1);
    rerender(<SessionTurns onSelect={onSelect} selectedTurnId={T1} />);
    expect(screen.getAllByRole("button", { name: /open timeline|shown below/i })[1]).toHaveAttribute("aria-pressed", "true");
  });

  it("loads a deep-linked session on mount without overriding an explicitly selected turn", async () => {
    listSessionTurns.mockResolvedValue({ agentId: AGENT, sessionId: "sess-1", turns });
    const onSelect = vi.fn();
    render(<SessionTurns initialAgentId={AGENT} initialSessionId="sess-1" selectedTurnId={T1} onSelect={onSelect} />);
    await screen.findByLabelText("2 journaled turns");
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("explains how to turn the journal on when the session has no turns", async () => {
    listSessionTurns.mockResolvedValue({ agentId: AGENT, sessionId: "sess-1", turns: [] });
    render(<SessionTurns onSelect={vi.fn()} />);
    fill();
    await screen.findByText("No journaled turns in this session");
    expect(screen.getByText(/app\.turn-journal\.enabled=true/)).toBeInTheDocument();
  });

  it("shows forbidden, unavailable and generic errors, and retries", async () => {
    listSessionTurns.mockRejectedValueOnce(Object.assign(new Error("x"), { status: 403 }));
    render(<SessionTurns onSelect={vi.fn()} />);
    fill();
    await screen.findByText("You cannot view this journal");
    listSessionTurns.mockRejectedValueOnce(Object.assign(new Error("x"), { status: 404 }));
    fireEvent.click(screen.getByRole("button", { name: /find turns/i }));
    await screen.findByText("Session lookup is not available");
    listSessionTurns.mockRejectedValueOnce(Object.assign(new Error("boom"), { status: 500 }));
    fireEvent.click(screen.getByRole("button", { name: /find turns/i }));
    await screen.findByText("boom");
    listSessionTurns.mockResolvedValueOnce({ agentId: AGENT, sessionId: "sess-1", turns });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.getAllByTestId("session-turn")).toHaveLength(2));
  });
});
