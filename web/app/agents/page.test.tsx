// The /agents page and the deep links the playground's Run settings drawer builds (?agentId, view=workbench,
// section=behavior, focus=<setting or group>): with an agent the workbench opens (it reads section and focus
// itself); without one the choice of agent keeps the request through to the agent that is picked.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { i18nMock } from "../components/agents/testI18n";
import { agentBehaviorHref } from "../components/agents/playground/runSettingsModel";

const search = vi.hoisted(() => ({ value: "" }));
const workbench = vi.hoisted(() => ({ props: [] as Array<{ selectedAgentId: string | null }> }));

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(search.value),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/agents",
}));
vi.mock("../lib/i18n", () => i18nMock);
vi.mock("../lib/api", () => ({
  api: {
    listProviders: vi.fn(async () => []),
    listAgents: vi.fn(async () => [{ id: "a1", name: "Mailer" }]),
    listCollections: vi.fn(async () => []),
  },
}));
vi.mock("../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../components/ax/PageHeader", () => ({ default: ({ title }: { title: string }) => <h1>{title}</h1> }));
vi.mock("../components/setup/SetupLinks", () => ({ SetupHint: (): null => null }));
vi.mock("../components/RouteTransition", () => ({ usePageReady: vi.fn() }));
vi.mock("../components/agents/AgentWorkbench", () => ({
  default: (props: { selectedAgentId: string | null }) => {
    workbench.props.push(props);
    return <div data-testid="workbench">{props.selectedAgentId}</div>;
  },
}));
vi.mock("../components/agents/AgentsIndex", () => ({
  default: ({ agents, agentHref }: { agents: Array<{ id: string; name: string }>; agentHref: (id: string) => string }) => (
    <ul data-testid="index">
      {agents.map((a) => (
        <li key={a.id}>
          <a href={agentHref(a.id)}>{a.name}</a>
        </li>
      ))}
    </ul>
  ),
}));

import AgentsPage from "./page";

beforeEach(() => {
  workbench.props.length = 0;
});
afterEach(() => cleanup());

describe("/agents deep links from the Run settings drawer", () => {
  it.each(["agent.limits", "agent.limits.maxToolSteps"])("opens the workbench on the agent for the link focused on %s", async (focus) => {
    const href = agentBehaviorHref("a1", focus);
    search.value = href.slice(href.indexOf("?") + 1);
    render(<AgentsPage />);
    expect(await screen.findByTestId("workbench")).toHaveTextContent("a1");
    expect(screen.queryByTestId("index")).toBeNull();
  });

  it("without an agent, asks which one and carries section and focus into each agent's link", async () => {
    search.value = "section=behavior&focus=agent.limits.maxToolSteps";
    render(<AgentsPage />);
    expect(await screen.findByTestId("agents-pick-for-behavior")).toBeInTheDocument();
    const link = await screen.findByRole("link", { name: "Mailer" });
    await waitFor(() => expect(link.getAttribute("href")).toBe("/agents?agentId=a1&section=behavior&focus=agent.limits.maxToolSteps"));
  });
});
