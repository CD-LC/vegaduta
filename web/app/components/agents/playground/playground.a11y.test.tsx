// Accessibility (axe) of the playground's live status chips, turn-outcome notices, stop-limit actions and the
// turn-timeline links.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { axeViolations } from "../../../lib/axeTestKit";
import { i18nMock } from "../testI18n";

vi.mock("../../../lib/i18n", () => i18nMock);
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import { LiveStatusBar } from "./LiveStatusBar";
import { TurnOutcomeNotice } from "./TurnOutcomeNotice";
import { StopLimitAction } from "./StopLimitAction";
import ViewTurnLink from "../ViewTurnLink";
import CopyTurnId from "../../turns/CopyTurnId";
import type { LiveChip, LiveChipKind } from "./liveStatus";
import type { PlaygroundAgent } from "./types";

afterEach(() => cleanup());

const EDITOR: PlaygroundAgent = { id: "a1", name: "Mailer", accessLevel: "EDITOR" } as PlaygroundAgent;
const RUNNER: PlaygroundAgent = { id: "a1", name: "Mailer", accessLevel: "RUNNER" } as PlaygroundAgent;

const KINDS: LiveChipKind[] = ["waitingApproval", "preparing", "modelCall", "thinking", "tool", "writing", "continuing", "retrying", "failover", "toolsOff", "failoverBlocked"];
const chips: LiveChip[] = KINDS.map((kind, i) => ({
  id: `c${i}`,
  kind,
  tone: kind === "retrying" || kind === "failover" || kind === "failoverBlocked" ? "attention" : "progress",
  tool: "send_email",
  attempt: 1,
  maxAttempts: 3,
  pass: 1,
  maxPasses: 2,
  count: 1,
  model: "gpt-x",
}));

describe("playground notices accessibility (axe)", () => {
  it("the live status bar with every chip, and empty", async () => {
    const { unmount } = render(<LiveStatusBar chips={chips} />);
    expect(await axeViolations()).toEqual([]);
    unmount();
    render(<LiveStatusBar chips={[]} />);
    expect(await axeViolations()).toEqual([]);
  });

  it.each([
    [{ kind: "truncated", source: "event" }, EDITOR],
    [{ kind: "truncated", source: "event" }, RUNNER],
    [{ kind: "refused", reason: "policy", source: "event" }, EDITOR],
    [{ kind: "malformedToolCall", source: "event" }, EDITOR],
    [{ kind: "empty", source: "event" }, EDITOR],
  ] as const)("the turn outcome notice %j for %s", async (outcome, agent) => {
    render(<TurnOutcomeNotice outcome={outcome as never} agent={agent} canContinue onContinue={vi.fn()} />);
    expect(await axeViolations()).toEqual([]);
  });

  it("the stop-limit action for an editor and for someone who must ask", async () => {
    const { unmount } = render(<StopLimitAction agent={EDITOR} kind={"max_tool_steps" as never} />);
    expect(await axeViolations()).toEqual([]);
    unmount();
    render(<StopLimitAction agent={RUNNER} kind={"run_budget" as never} />);
    expect(await axeViolations()).toEqual([]);
  });

  it("the turn timeline link and the copy-turn-id button", async () => {
    render(
      <div>
        <ViewTurnLink agentId="a1" sessionId="s1" turnId="t1" />
        <CopyTurnId turnId="22222222-2222-4222-8222-222222222222" />
      </div>
    );
    expect(await axeViolations()).toEqual([]);
  });
});
