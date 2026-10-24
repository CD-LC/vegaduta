// The agent-playground preview page, rendered with the sample-data fetch mock: the drawer's reads are answered by
// components/agents/playground/runControlsMock.ts, and the seeded transcript shows the cut-off and step-limit
// notices. Guards the mock routes against drifting from the endpoints the drawer calls.

import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { i18nMock } from "../../../components/agents/testI18n";

vi.mock("../../../lib/i18n", () => ({ ...i18nMock, useLocale: () => "en" }));
vi.mock("../../../components/agents/IntelligenceSourceControl", () => ({ default: (): null => null, getEffectiveEdgeOptIn: async () => true }));
vi.mock("../../../components/agents/CallPanel", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/CameraPanel", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/ShareAgentPanel", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/BrowserTakeoverPanel", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/RunCostCard", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/SaveRunAsRecipe", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/TurnFeedback", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/RequestInspector", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/lab/AgentLab", () => ({ default: (): null => null }));
vi.mock("../../../components/agents/lab/AgentLabLauncher", () => ({ default: (): null => null }));
vi.mock("../../../components/edge/OnDeviceSetupNudge", () => ({ default: (): null => null }));
vi.mock("../../../components/SandboxDataHint", () => ({ default: (): null => null }));
vi.mock("../../../components/PlatformModelChangeNotice", () => ({ default: (): null => null }));
vi.mock("../../../agents/quality/SaveAsEvalCaseButton", () => ({ default: (): null => null }));
vi.mock("../../../lib/edge/useWebMcpTools", () => ({ useWebMcpTools: vi.fn() }));
vi.mock("../../../lib/edge/tier1", () => ({ prewarmTier1Engine: vi.fn(), getTier1RunModel: vi.fn(() => null) }));
vi.mock("../../../lib/edge/tier0", () => ({ tier0Available: vi.fn(async () => false) }));
vi.mock("../../../lib/edge/tierRouter", () => ({ routeTurn: vi.fn(), resetEdgeSession: vi.fn() }));
vi.mock("../../../components/AppShell", () => ({ default: ({ children }: { children: unknown }) => <>{children as never}</> }));

beforeAll(() => {
  HTMLMediaElement.prototype.pause = vi.fn();
  HTMLMediaElement.prototype.load = vi.fn();
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

describe("console-preview/agent-playground", () => {
  it("opens with the Run settings drawer filled from the sample mock", async () => {
    const { default: Preview } = await import("./page");
    render(<Preview />);
    const drawer = await screen.findByTestId("run-settings-drawer");
    expect(await within(drawer).findByTestId("rs-limit-maxToolSteps")).toBeTruthy();
    expect(await within(drawer).findByTestId("rs-tool-send_email")).toBeTruthy();
    expect(within(drawer).getByTestId("rs-model").textContent).toContain("gpt-4o-mini");
  });

  it("shows how the seeded replies ended", async () => {
    const { default: Preview } = await import("./page");
    render(<Preview />);
    expect(await screen.findByTestId("turn-outcome-truncated")).toBeTruthy();
    expect(await screen.findByTestId("turn-stop-step_limit")).toBeTruthy();
    expect(screen.getByTestId("stop-limit-raise")).toBeTruthy();
  });
});
