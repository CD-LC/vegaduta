import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const listSessionTurns = vi.hoisted(() => vi.fn());
const getTurnJournal = vi.hoisted(() => vi.fn());
const search = vi.hoisted(() => ({ value: "" }));

vi.mock("../../lib/api/turns", () => ({ listSessionTurns, getTurnJournal }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(search.value) }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/ax/PageHeader", () => ({ default: ({ title }: { title: string }) => <h1>{title}</h1> }));
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

import TurnsPage from "./page";
import { buildChain, SPECS } from "../../components/turns/chainTestUtil";

const AGENT = "11111111-1111-4111-8111-111111111111";
const NEWEST = "22222222-2222-4222-8222-222222222222";
const OLDER = "33333333-3333-4333-8333-333333333333";

async function journalFor(turnId: string) {
  const events = (await buildChain(SPECS)).map((e) => ({ ...e, turnId }));
  return { agentId: AGENT, turnId, chainIntact: true, brokenAtSeq: -1, verification: "ok", events };
}

describe("turn timeline page", () => {
  beforeEach(() => {
    listSessionTurns.mockReset();
    getTurnJournal.mockReset();
    listSessionTurns.mockResolvedValue({
      agentId: AGENT,
      sessionId: "sess-1",
      turns: [
        { turnId: NEWEST, agentId: AGENT, startedAt: "2026-10-15T10:05:00Z", lastEventAt: "2026-10-15T10:05:02Z", events: 4, status: "completed" },
        { turnId: OLDER, agentId: AGENT, startedAt: "2026-10-15T10:00:00Z", lastEventAt: "2026-10-15T10:00:01Z", events: 4, status: "completed" },
      ],
    });
    getTurnJournal.mockImplementation((_a: string, t: string) => journalFor(t));
  });

  it("opens the newest turn of the session a chat message linked to", async () => {
    search.value = `agentId=${AGENT}&sessionId=sess-1`;
    render(<TurnsPage />);
    await screen.findByText("Chain intact");
    expect(listSessionTurns).toHaveBeenCalledWith(AGENT, "sess-1");
    expect(getTurnJournal).toHaveBeenCalledWith(AGENT, NEWEST);
    expect(screen.getByRole("list", { name: "Timeline" })).toBeInTheDocument();
    expect(screen.getAllByText(NEWEST).length).toBeGreaterThan(0);
  });

  it("switches to another turn of the session on request", async () => {
    search.value = `agentId=${AGENT}&sessionId=sess-1`;
    render(<TurnsPage />);
    await screen.findByText("Chain intact");
    fireEvent.click(screen.getAllByRole("button", { name: "Open timeline" })[0]);
    await waitFor(() => expect(getTurnJournal).toHaveBeenCalledWith(AGENT, OLDER));
    await screen.findByText("Chain intact");
  });

  it("honours an explicit turn id and does not replace it with the newest", async () => {
    search.value = `agentId=${AGENT}&sessionId=sess-1&turnId=${OLDER}`;
    render(<TurnsPage />);
    await screen.findByText("Chain intact");
    expect(getTurnJournal).toHaveBeenCalledTimes(1);
    expect(getTurnJournal).toHaveBeenCalledWith(AGENT, OLDER);
  });

  it("still opens a single turn from agentId and turnId alone", async () => {
    search.value = `agentId=${AGENT}&turnId=${NEWEST}`;
    render(<TurnsPage />);
    await screen.findByText("Chain intact");
    expect(listSessionTurns).not.toHaveBeenCalled();
    expect(getTurnJournal).toHaveBeenCalledWith(AGENT, NEWEST);
  });
});
