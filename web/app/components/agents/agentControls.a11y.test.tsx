// Accessibility (axe) of the smaller agent controls added for approvals: the approval-mode card with its tier
// comparison, and the "carrying on" status in each state.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";
import { axeViolations } from "../../lib/axeTestKit";

const listAgents = vi.fn();
vi.mock("../../lib/api", () => ({ api: { get listAgents() { return listAgents; } } }));
vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import AgentApprovalModeCard from "./AgentApprovalModeCard";
import ApprovalContinuationStatus from "./ApprovalContinuationStatus";

const base: {
  continuing: boolean;
  problem: "notStarted" | "timedOut" | null;
  outcome: "completed" | "failed" | null;
  dismiss: () => void;
} = { continuing: false, problem: null, outcome: null, dismiss: vi.fn() };

beforeEach(() => listAgents.mockReset());
afterEach(() => cleanup());

describe("agent approval controls accessibility (axe)", () => {
  it.each(["SUPERVISED", "SEMI_AUTONOMOUS", "FULLY_AUTONOMOUS"])("the approval-mode card for %s, collapsed and with the tier comparison open", async (tier) => {
    listAgents.mockResolvedValue([{ id: "a1", autonomyTier: tier }]);
    render(<AgentApprovalModeCard agentId="a1" />);
    const toggle = await screen.findByTestId("agent-approval-tiers-toggle");
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(toggle);
    expect(screen.getByTestId("autonomy-tier-explainer")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
  });

  it.each([
    ["carrying on", { continuing: true }],
    ["finished", { outcome: "completed" as const }],
    ["failed", { outcome: "failed" as const }],
    ["did not start", { problem: "notStarted" as const }],
    ["timed out", { problem: "timedOut" as const }],
  ])("the continuation status when it is %s", async (_name, over) => {
    render(<ApprovalContinuationStatus continuation={{ ...base, ...over }} />);
    expect(await axeViolations()).toEqual([]);
  });
});
