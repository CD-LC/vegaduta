import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";

const listAgents = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    get listAgents() {
      return listAgents;
    },
  },
}));
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
import AgentSafetyControls from "./AgentSafetyControls";

vi.mock("./AgentRunBudgetField", () => ({ default: (): null => null }));
vi.mock("./AgentToolPoliciesCard", () => ({ default: (): React.ReactNode => <div data-testid="tool-policies-stub" /> }));

beforeEach(() => {
  listAgents.mockReset();
});
afterEach(() => cleanup());

describe("AgentApprovalModeCard", () => {
  it.each([
    ["SUPERVISED", "every tool call waits for a person"],
    ["SEMI_AUTONOMOUS", "each tool follows the workspace's rule"],
    ["FULLY_AUTONOMOUS", "never waits for approval"],
  ])("explains %s in plain language", async (tier, phrase) => {
    listAgents.mockResolvedValue([{ id: "a1", autonomyTier: tier }]);
    render(<AgentApprovalModeCard agentId="a1" />);
    expect(await screen.findByTestId("agent-approval-mode-note")).toHaveTextContent(phrase);
  });

  it("links to this agent's requests, and to tool rules only where rules apply", async () => {
    listAgents.mockResolvedValue([{ id: "a1", autonomyTier: "SEMI_AUTONOMOUS" }, { id: "a2", autonomyTier: "SUPERVISED" }]);
    const { unmount } = render(<AgentApprovalModeCard agentId="a1" />);
    expect(await screen.findByTestId("agent-approval-queue-link")).toHaveAttribute("href", "/admin/approvals?agent=a1");
    expect(screen.getByTestId("agent-approval-rules-link")).toHaveAttribute("href", "/admin/approvals?rules=1");
    unmount();
    render(<AgentApprovalModeCard agentId="a2" />);
    await screen.findByTestId("agent-approval-queue-link");
    expect(screen.queryByTestId("agent-approval-rules-link")).toBeNull();
  });

  it("compares the three levels on demand, highlighting this agent's", async () => {
    listAgents.mockResolvedValue([{ id: "a1", autonomyTier: "FULLY_AUTONOMOUS" }]);
    render(<AgentApprovalModeCard agentId="a1" />);
    const toggle = await screen.findByTestId("agent-approval-tiers-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const explainer = screen.getByTestId("autonomy-tier-explainer");
    expect(within(explainer).getByTestId("tier-card-fully_autonomous")).toHaveAttribute("aria-current", "true");
    expect(within(explainer).getByTestId("tier-card-supervised")).not.toHaveAttribute("aria-current");
  });

  it("hides itself when the agent cannot be read", async () => {
    listAgents.mockRejectedValue(new Error("offline"));
    const { container } = render(<AgentApprovalModeCard agentId="a1" />);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(listAgents).toHaveBeenCalledTimes(1);
    expect(container.firstChild).toBeNull();
  });

  it("is mounted with the other per-agent safety controls", async () => {
    listAgents.mockResolvedValue([{ id: "a1", autonomyTier: "SUPERVISED" }]);
    render(<AgentSafetyControls agentId="a1" />);
    expect(await screen.findByTestId("agent-approval-mode")).toBeInTheDocument();
    expect(screen.getByTestId("tool-policies-stub")).toBeInTheDocument();
  });
});
