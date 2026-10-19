import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getTurnJournal = vi.hoisted(() => vi.fn());
const download = vi.hoisted(() => vi.fn());

vi.mock("../../lib/api/turns", () => ({ getTurnJournal }));
vi.mock("../../lib/api/core", () => ({ triggerBrowserDownload: download }));
vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import TurnInspector from "./TurnInspector";
import { buildChain, SPECS } from "./chainTestUtil";

const AGENT = "11111111-1111-4111-8111-111111111111";
const TURN = "22222222-2222-4222-8222-222222222222";

function readBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsText(blob);
  });
}

async function good() {
  const events = await buildChain(SPECS);
  return { agentId: AGENT, turnId: TURN, chainIntact: true, brokenAtSeq: -1, verification: "ok", events };
}

function submit() {
  fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: AGENT } });
  fireEvent.change(screen.getByLabelText("Turn ID"), { target: { value: TURN } });
  fireEvent.click(screen.getByRole("button", { name: /inspect turn/i }));
}

describe("TurnInspector", () => {
  beforeEach(() => {
    getTurnJournal.mockReset();
    download.mockReset();
  });

  it("rejects non-UUID input without calling the API", () => {
    render(<TurnInspector />);
    fireEvent.change(screen.getByLabelText("Agent ID"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: /inspect turn/i }));
    expect(screen.getByRole("alert")).toHaveTextContent(/must both be UUIDs/);
    expect(getTurnJournal).not.toHaveBeenCalled();
  });

  it("renders the timeline, verifies the chain in the browser and exports a fixture", async () => {
    getTurnJournal.mockResolvedValue(await good());
    render(<TurnInspector />);
    submit();
    await screen.findByText("Chain intact");
    await waitFor(() => expect(screen.getByTestId("client-verdict")).toHaveTextContent("all 4 hashes match"));
    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    expect(screen.getByText("COMMIT")).toBeInTheDocument();
    expect(screen.getByText("Not recorded")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /export as test fixture/i }));
    expect(download).toHaveBeenCalledTimes(1);
    const [blob, name] = download.mock.calls[0];
    expect(name).toBe(`turn-${TURN}.fixture.json`);
    expect(JSON.parse(await readBlob(blob as Blob)).schema).toBe("vegaduta.turn-fixture/1");
  });

  it("flags a tampered journal even when the server says intact", async () => {
    const v = await good();
    v.events[1] = { ...v.events[1], summary: "provider=evil" };
    getTurnJournal.mockResolvedValue(v);
    render(<TurnInspector />);
    submit();
    await screen.findByText(/Chain broken at event 1/);
    expect(screen.getByText(/disagree/)).toBeInTheDocument();
  });

  it("explains how to enable the journal on 404", async () => {
    getTurnJournal.mockRejectedValue(Object.assign(new Error("x"), { status: 404 }));
    render(<TurnInspector />);
    submit();
    await screen.findByText("No journal for this turn");
    expect(screen.getByText(/app\.turn-journal\.enabled=true/)).toBeInTheDocument();
  });

  it("shows forbidden and generic errors with a retry", async () => {
    getTurnJournal.mockRejectedValueOnce(Object.assign(new Error("x"), { status: 403 }));
    render(<TurnInspector />);
    submit();
    await screen.findByText("You cannot view this journal");
    getTurnJournal.mockRejectedValueOnce(Object.assign(new Error("boom"), { status: 500 }));
    submit();
    await screen.findByText("boom");
    getTurnJournal.mockResolvedValueOnce(await good());
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await screen.findByText("Chain intact");
  });

  it("loads a deep-linked turn on mount", async () => {
    getTurnJournal.mockResolvedValue(await good());
    render(<TurnInspector initialAgentId={AGENT} initialTurnId={TURN} />);
    await screen.findByText("Chain intact");
    expect(getTurnJournal).toHaveBeenCalledWith(AGENT, TURN);
  });
});
