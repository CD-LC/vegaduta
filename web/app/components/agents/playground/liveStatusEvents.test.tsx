// The live chips and the "Continue" / refusal notices, driven by the lifecycle step events core sends, rendered with
// the real English bundle: no chip and no notice reads reply text, so rewording or translating it changes nothing.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { i18nMock } from "../testI18n";

vi.mock("../../../lib/i18n", () => i18nMock);
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import { normalizeServerStep } from "../AgentActivityTrace";
import { LiveStatusBar } from "./LiveStatusBar";
import { TurnOutcomeNotice } from "./TurnOutcomeNotice";
import { deriveLiveChips } from "./liveStatus";
import { deriveTurnOutcome } from "./turnOutcome";
import type { PlaygroundAgent } from "./types";

afterEach(() => cleanup());

const steps = (...events: Array<Record<string, unknown>>) => events.map((e, i) => normalizeServerStep(e, i + 1));
const base = { sending: true, hasText: true, waitingApprovals: 0, approvalContinuing: false };
const AGENT = { id: "a1", name: "Mailer", accessLevel: "RUNNER" } as PlaygroundAgent;

function bar(events: Array<Record<string, unknown>>, extra: Partial<Parameters<typeof deriveLiveChips>[0]> = {}) {
  return render(<LiveStatusBar chips={deriveLiveChips({ ...base, steps: steps(...events), ...extra })} />);
}

describe("the live status bar from lifecycle events", () => {
  it("says the agent is trying again, with the try and the reason in the bundle's words", () => {
    bar([{ type: "retry", attempt: 2, maxAttempts: 3, reason: "rate_limited" }]);
    expect(screen.getByTestId("chip-retrying").textContent).toBe("Trying again (attempt 2 of 3): rate limited");
  });

  it("says a backup model took over, naming it", () => {
    bar([{ type: "failover", toModel: "gpt-4o-mini", reason: "timeout" }]);
    expect(screen.getByTestId("chip-failover").textContent).toBe("Switched to gpt-4o-mini: timed out");
  });

  it("shows a reason code this build has no sentence for as plain words", () => {
    bar([{ type: "retry", attempt: 2, maxAttempts: 2, reason: "quota_exhausted_for_today" }]);
    expect(screen.getByTestId("chip-retrying").textContent).toBe("Trying again (attempt 2 of 2): quota exhausted for today");
  });

  it("does not treat an inherited object key as a known reason", () => {
    bar([{ type: "retry", attempt: 2, maxAttempts: 2, reason: "constructor" }]);
    expect(screen.getByTestId("chip-retrying").textContent).toBe("Trying again (attempt 2 of 2): constructor");
  });

  it("says the reply is being continued, pass by pass", () => {
    bar([{ type: "continuation", pass: 2, maxPasses: 3, reason: "cut_off" }]);
    expect(screen.getByTestId("chip-continuing").textContent).toBe("Continuing the reply (pass 2 of 3)");
  });

  it("says the agent is carrying on after a decision, once", () => {
    bar([{ type: "continuation", pass: 1, maxPasses: 1, reason: "approval" }], { approvalContinuing: true });
    expect(screen.getAllByTestId("chip-continuing")).toHaveLength(1);
    expect(screen.getByTestId("chip-continuing").textContent).toBe("Carrying on after your decision");
  });

  it("says the turn is waiting for approval until a later tool result", () => {
    bar([
      { type: "tool_call", id: 1, tool: "send_email", args: "{}" },
      { type: "tool_result", id: 1, tool: "send_email", preview: "waiting", ms: 3 },
      { type: "approval_required", tool: "send_email", approvalId: "c0ffee00-0000-4000-8000-000000000001" },
    ]);
    expect(screen.getByTestId("chip-waitingApproval")).toBeTruthy();
  });
});

describe("the outcome notice from the finish event", () => {
  const render_ = (events: Array<Record<string, unknown>>, content: string, onContinue = vi.fn()) => {
    const outcome = deriveTurnOutcome({ steps: steps(...events), content });
    expect(outcome).not.toBeNull();
    render(<TurnOutcomeNotice outcome={outcome!} agent={AGENT} canContinue onContinue={onContinue} />);
    return onContinue;
  };

  it("offers Continue for a cut-off reply whatever the reply text says", () => {
    const onContinue = render_([{ type: "finish", finishReason: "length" }], "Ein Teil der Antwort ... [gekürzt]");
    fireEvent.click(screen.getByTestId("outcome-continue"));
    expect(onContinue).toHaveBeenCalledTimes(1);
  });

  it("offers Continue for a reply broken off after some text", () => {
    render_([{ type: "finish", finishReason: "interrupted" }], "Part of an answer, in a language with no marker");
    expect(screen.getByTestId("outcome-continue")).toBeTruthy();
  });

  it("shows the refusal notice with the provider's word, with no English notice in the text", () => {
    render_([{ type: "finish", finishReason: "safety", detail: "content_filter" }], "");
    expect(screen.getByTestId("turn-outcome-refused").textContent).toContain("content filter");
    expect(screen.queryByTestId("outcome-continue")).toBeNull();
  });

  it("shows no notice for an answer, even when its text mentions the old marker", () => {
    expect(deriveTurnOutcome({ steps: steps({ type: "finish", finishReason: "stop" }), content: "see [reply interrupted]" })).toBeNull();
  });
});
