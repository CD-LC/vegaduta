import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

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

import TurnTimeline from "./TurnTimeline";
import { buildChain } from "./chainTestUtil";

const SPECS = [
  { type: "INTAKE", summary: "messageChars=12 attachments=0 tools=2" },
  { type: "MODEL_CALL", summary: "provider=openai model=gpt-x route=primary" },
  { type: "TOOL_CALL", summary: "tool=lookup status=ok resultSha=abc123 ms=120" },
  { type: "TOOL_CALL", summary: "tool=send_email status=parked resultSha=def456 ms=3" },
  { type: "APPROVAL", summary: "tool=send_email decision=pending request=aaaa" },
  { type: "APPROVAL", summary: "tool=send_email decision=decided:completed request=aaaa" },
  { type: "GUARD", summary: "guard=continuation outcome=pass-1" },
  { type: "MODEL_CALL", summary: "provider=anthropic model=claude route=fallback" },
  { type: "GUARD", summary: "guard=outbound-enforced outcome=withheld" },
  { type: "GUARD", summary: "guard=response-cache outcome=hit" },
  { type: "COMMIT", summary: "status=completed answerChars=40 tokensIn=100 tokensOut=40" },
];

async function renderTimeline(brokenAtSeq: number | null = null) {
  const events = await buildChain(SPECS);
  render(<TurnTimeline events={events} brokenAtSeq={brokenAtSeq} />);
  return events;
}

describe("TurnTimeline", () => {
  it("describes each event in plain language, with its reason", async () => {
    await renderTimeline();
    expect(screen.getAllByRole("listitem")).toHaveLength(SPECS.length);
    expect(screen.getByText("Model call to gpt-x (openai), route primary")).toBeInTheDocument();
    expect(screen.getByText("Tool call lookup: ok")).toBeInTheDocument();
    expect(screen.getByText("Approval requested before running send_email")).toBeInTheDocument();
    expect(screen.getByText("Approval decided for send_email: completed")).toBeInTheDocument();
    expect(screen.getByText("Continuation: continuation (pass-1)")).toBeInTheDocument();
    expect(screen.getByText("Failed over to claude (anthropic)")).toBeInTheDocument();
    expect(screen.getByText(/primary model failed, so the turn moved to the agent's fallback provider/)).toBeInTheDocument();
    expect(screen.getByText(/waiting for a person to approve it; the tool has not run/)).toBeInTheDocument();
    expect(screen.getByText("Guardrail outbound-enforced: withheld")).toBeInTheDocument();
    expect(screen.getByText("Answered from the response cache")).toBeInTheDocument();
    expect(screen.getByText("Finished: completed (40 characters in the answer)")).toBeInTheDocument();
  });

  it("shows durations and tokens on the events that carry them", async () => {
    await renderTimeline();
    const tool = screen.getByRole("listitem", { name: "Event 2: TOOL_CALL" });
    expect(within(tool).getByText("120 ms")).toBeInTheDocument();
    const commit = screen.getByRole("listitem", { name: "Event 10: COMMIT" });
    expect(within(commit).getByText("100 / 40")).toBeInTheDocument();
  });

  it("keeps hashes behind a details control, labelling a tool's hash as its arguments", async () => {
    await renderTimeline();
    const tool = screen.getByRole("listitem", { name: "Event 2: TOOL_CALL" });
    expect(within(tool).getByText("Arguments hash:")).toBeInTheDocument();
    expect(within(tool).getByText("abc123")).toBeInTheDocument();
    expect(within(tool).getByText("Hashes and raw summary").tagName).toBe("SUMMARY");
  });

  it("filters by event type with toggle buttons and says how many are shown", async () => {
    await renderTimeline();
    const tools = screen.getByRole("button", { name: /^Tools/ });
    expect(tools).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(tools);
    expect(tools).toHaveAttribute("aria-pressed", "true");
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByRole("status")).toHaveTextContent("Showing 2 of 11 events");
    fireEvent.click(screen.getByRole("button", { name: /^Approvals/ }));
    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getAllByRole("listitem")).toHaveLength(SPECS.length);
  });

  it("only offers event types the turn actually has", async () => {
    await renderTimeline();
    expect(screen.queryByRole("button", { name: /^Checks/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Retries and failover/ })).toBeInTheDocument();
  });

  it("filters to problems only", async () => {
    await renderTimeline();
    fireEvent.click(screen.getByRole("switch", { name: "Only problems" }));
    // parked tool, pending approval, failover, withheld guardrail
    expect(screen.getAllByRole("listitem")).toHaveLength(4);
    expect(screen.queryByText("Tool call lookup: ok")).not.toBeInTheDocument();
  });

  it("searches the raw summaries and explains an empty result", async () => {
    await renderTimeline();
    fireEvent.change(screen.getByLabelText("Search events"), { target: { value: "CLAUDE" } });
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
    fireEvent.change(screen.getByLabelText("Search events"), { target: { value: "nothing-like-this" } });
    expect(screen.queryAllByRole("listitem")).toHaveLength(0);
    expect(screen.getByText("No events match these filters.")).toBeInTheDocument();
  });

  it("marks events from the broken link onwards", async () => {
    await renderTimeline(9);
    expect(screen.getByRole("listitem", { name: "Event 8: GUARD" }).className).not.toContain("border-destructive");
    expect(screen.getByRole("listitem", { name: "Event 9: GUARD" }).className).toContain("border-destructive");
    expect(screen.getByRole("listitem", { name: "Event 10: COMMIT" }).className).toContain("border-destructive");
  });

  it("states that arguments and results are never stored", async () => {
    await renderTimeline();
    expect(screen.getByText(/Arguments and results are never stored/)).toBeInTheDocument();
  });

  it("shows cost as money, not as a bare number", async () => {
    const events = await buildChain([{ type: "COMMIT", summary: "status=completed answerChars=4 costMinor=250" }]);
    render(<TurnTimeline events={events} brokenAtSeq={null} />);
    expect(screen.getByText("$2.50")).toBeInTheDocument();
  });
});
