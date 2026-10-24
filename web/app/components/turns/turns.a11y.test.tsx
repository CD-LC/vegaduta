// Accessibility (axe) of the turn inspector: the empty form, a verified timeline, a tampered journal and the session list.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { axeViolations } from "../../lib/axeTestKit";

const getTurnJournal = vi.hoisted(() => vi.fn());
const listSessionTurns = vi.hoisted(() => vi.fn());

vi.mock("../../lib/api/turns", () => ({ getTurnJournal, listSessionTurns }));
vi.mock("../../lib/api/core", () => ({ triggerBrowserDownload: vi.fn() }));
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

import TurnInspector from "./TurnInspector";
import SessionTurns from "./SessionTurns";
import { buildChain, SPECS } from "./chainTestUtil";

const AGENT = "11111111-1111-4111-8111-111111111111";
const TURN = "22222222-2222-4222-8222-222222222222";

beforeEach(() => {
  getTurnJournal.mockReset();
  listSessionTurns.mockReset();
});

function inspect() {
  fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: AGENT } });
  fireEvent.change(screen.getByLabelText("Turn ID"), { target: { value: TURN } });
  fireEvent.click(screen.getByRole("button", { name: /inspect turn/i }));
}

describe("turn inspector accessibility (axe)", () => {
  it("the empty form and its validation error", async () => {
    render(<TurnInspector />);
    expect(await axeViolations()).toEqual([]);
    fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: /inspect turn/i }));
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it("a verified timeline", async () => {
    const events = await buildChain(SPECS);
    getTurnJournal.mockResolvedValue({ agentId: AGENT, turnId: TURN, chainIntact: true, brokenAtSeq: -1, verification: "ok", events });
    render(<TurnInspector />);
    inspect();
    await screen.findByText("Chain intact");
    await waitFor(() => expect(screen.getByTestId("client-verdict")).toHaveTextContent("all 4 hashes match"));
    expect(await axeViolations()).toEqual([]);
  });

  it("a tampered journal", async () => {
    const events = await buildChain(SPECS);
    events[1] = { ...events[1], summary: "provider=evil" };
    getTurnJournal.mockResolvedValue({ agentId: AGENT, turnId: TURN, chainIntact: true, brokenAtSeq: -1, verification: "ok", events });
    render(<TurnInspector />);
    inspect();
    await screen.findByTestId("client-verdict");
    await waitFor(() => expect(screen.getByTestId("client-verdict")).not.toHaveTextContent("all 4 hashes match"));
    expect(await axeViolations()).toEqual([]);
  });

  it("the session's turn list", async () => {
    listSessionTurns.mockResolvedValue({ agentId: AGENT, sessionId: "sess-1", turns: [
      { turnId: TURN, agentId: AGENT, startedAt: "2026-10-15T10:05:00Z", lastEventAt: "2026-10-15T10:05:02Z", events: 7, status: "error:Timeout" },
      { turnId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", agentId: AGENT, startedAt: "2026-10-15T10:00:00Z", lastEventAt: "2026-10-15T10:00:01Z", events: 4, status: "completed" },
    ] });
    render(<SessionTurns onSelect={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: AGENT } });
    fireEvent.change(screen.getByLabelText("Session ID"), { target: { value: "sess-1" } });
    fireEvent.click(screen.getByRole("button", { name: /find turns/i }));
    await waitFor(() => expect(listSessionTurns).toHaveBeenCalled());
    await screen.findByLabelText("2 journaled turns");
    expect(await axeViolations()).toEqual([]);
  });
});
