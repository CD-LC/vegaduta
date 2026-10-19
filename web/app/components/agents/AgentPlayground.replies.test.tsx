// Prod re-verification 2026-10-05 (R2 + R3), the workbench test chat
// (AgentPlayground):
//  - a hosted reply that follows a declined on-device attempt must be rendered
//    exactly like any other hosted reply: a code block AND the artifact offer;
//  - a failed turn stays in the transcript as a distinct error bubble (so it is
//    still there after a reload) with a "Try again" action.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ARTIFACTS_STORAGE_KEY } from "../../lib/artifacts/artifactsFlag";

const PAGE = [
  "<!doctype html>",
  "<html>",
  "<head><title>Hello</title></head>",
  "<body>",
  "  <h1>Hello</h1>",
  "  <p>A tiny single-page HTML page, long enough to be a deliverable and not a snippet.</p>",
  "  <script>document.querySelector('h1').textContent = 'Hello again';</script>",
  "</body>",
  "</html>",
].join("\n");
const REPLY = `Here is the page:\n\n\`\`\`html\n${PAGE}\n\`\`\`\n\nAnything to change?`;

const edge = vi.hoisted(() => ({ on: false }));
const chat = vi.hoisted(() => ({
  impl: null as null | ((onChunk: (c: string) => void, message: string) => Promise<string | null>),
  calls: [] as string[],
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../../lib/api", () => ({
  api: {
    streamChat: vi.fn(async (_a: string, message: string, _s: unknown, onChunk: (c: string) => void) => {
      chat.calls.push(message);
      if (chat.impl) return chat.impl(onChunk, message);
      onChunk(REPLY);
      return "session-1";
    }),
    listToolCalls: vi.fn(async () => []),
    getAgentSessionLlmUsage: vi.fn(async () => []),
    getAgentSessionLlmRequests: vi.fn(async () => []),
    getTenantSettings: vi.fn(async () => ({})),
    listProviders: vi.fn(async () => []),
    listProviderModels: vi.fn(async () => []),
    listCollections: vi.fn(async () => []),
    listFaceEnrollments: vi.fn(async () => []),
  },
}));
vi.mock("../../lib/edge/capabilities", async (importOriginal) => {
  const real = await importOriginal<typeof import("../../lib/edge/capabilities")>();
  return {
    ...real,
    edgeEnabled: () => edge.on,
    resolveAutomaticEdgeFallback: vi.fn(async () => true),
  };
});
vi.mock("../../lib/edge/tierRouter", () => ({
  routeTurn: vi.fn(async () => ({ handled: false, reason: "all-tiers-failed" })),
  resetEdgeSession: vi.fn(),
}));
vi.mock("./IntelligenceSourceControl", () => ({
  default: (): null => null,
  getEffectiveEdgeOptIn: async () => true,
}));
vi.mock("./CallPanel", () => ({ default: (): null => null }));
vi.mock("./CameraPanel", () => ({ default: (): null => null }));
vi.mock("./ShareAgentPanel", () => ({ default: (): null => null }));
vi.mock("./PendingToolApprovals", () => ({ default: (): null => null }));
vi.mock("./BrowserTakeoverPanel", () => ({ default: (): null => null }));
vi.mock("./RunCostCard", () => ({ default: (): null => null }));
vi.mock("./SaveRunAsRecipe", () => ({ default: (): null => null }));
vi.mock("./TurnFeedback", () => ({ default: (): null => null }));
vi.mock("./RequestInspector", () => ({ default: (): null => null }));
vi.mock("./lab/AgentLab", () => ({ default: (): null => null }));
vi.mock("./lab/AgentLabLauncher", () => ({ default: (): null => null }));
vi.mock("../edge/OnDeviceSetupNudge", () => ({ default: (): null => null }));
vi.mock("../SandboxDataHint", () => ({ default: (): null => null }));
vi.mock("../PlatformModelChangeNotice", () => ({ default: (): null => null }));
vi.mock("../../agents/quality/SaveAsEvalCaseButton", () => ({ default: (): null => null }));
vi.mock("../../lib/edge/useWebMcpTools", () => ({ useWebMcpTools: vi.fn() }));
vi.mock("../../lib/edge/tier1", () => ({
  prewarmTier1Engine: vi.fn(),
  getTier1RunModel: vi.fn(() => null),
}));
vi.mock("../../lib/edge/tier0", () => ({ tier0Available: vi.fn(async () => false) }));

const AGENT = { id: "agent-1", name: "e2e-agent-2", providerName: "OPENROUTER", model: "deepseek/deepseek-chat" };

beforeAll(() => {
  HTMLMediaElement.prototype.pause = vi.fn();
  HTMLMediaElement.prototype.load = vi.fn();
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});

async function mountPlayground() {
  const { default: AgentPlayground } = await import("./AgentPlayground");
  return render(<AgentPlayground agent={AGENT} />);
}

async function send(text: string) {
  const box = screen.getByPlaceholderText("sendPlaceholder") as HTMLTextAreaElement;
  fireEvent.change(box, { target: { value: text } });
  await act(async () => {
    fireEvent.keyDown(box, { key: "Enter" });
  });
}

describe("AgentPlayground - an html reply gets a code block and the artifact offer on every path", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(ARTIFACTS_STORAGE_KEY, "1");
    chat.impl = null;
    chat.calls.length = 0;
  });
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
    edge.on = false;
  });

  it("hosted reply (on-device off)", async () => {
    edge.on = false;
    await mountPlayground();
    await send("write an html page");
    expect(await screen.findByTestId("artifact-candidate-open")).toBeTruthy();
    expect(document.querySelector("pre code")).not.toBeNull();
  });

  it("hosted reply after the on-device attempt was declined (the 'Platform - local attempt failed' path)", async () => {
    edge.on = true;
    await mountPlayground();
    await send("write an html page");
    expect(await screen.findByTestId("artifact-candidate-open")).toBeTruthy();
    expect(document.querySelector("pre code")).not.toBeNull();
    expect(screen.getByText(/short all-tiers-failed|local attempt failed/i, { exact: false })).toBeTruthy();
  });
});

describe("AgentPlayground - a failed reply stays in the transcript", () => {
  const REJECTED = "The AI provider rejected our credentials. Check the API key.";

  beforeEach(() => {
    window.localStorage.clear();
    chat.impl = null;
    chat.calls.length = 0;
    edge.on = false;
  });
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  async function firstTurnThenFailure() {
    await mountPlayground();
    await send("hello");
    await screen.findByText(/Anything to change\?/);
    chat.impl = async () => {
      throw new Error(REJECTED);
    };
    await send("Say ok.");
    return screen.findByTestId("failed-reply");
  }

  it("shows the failure inline, live, as 'Reply failed' + the reason - not only as a banner", async () => {
    const bubble = await firstTurnThenFailure();
    expect(bubble.textContent).toContain("label");
    expect(bubble.textContent).toContain(REJECTED);
    // The reason is said once: the transient banner steps aside for the bubble.
    expect(screen.getAllByText(REJECTED)).toHaveLength(1);
    // role=alert is not used for a transcript entry.
    expect(bubble.querySelector('[role="alert"]')).toBeNull();
  });

  it("is still there after a reload (the symptom of prod bug 13)", async () => {
    await firstTurnThenFailure();
    cleanup();
    await mountPlayground();
    const bubble = await screen.findByTestId("failed-reply");
    expect(bubble.textContent).toContain(REJECTED);
    expect(screen.getByText("Say ok.")).toBeTruthy();
  });

  it("'Try again' sends the previous message again and replaces the failed exchange", async () => {
    await firstTurnThenFailure();
    chat.impl = null;
    chat.calls.length = 0;
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "tryAgainLabel" }));
    });
    await waitFor(() => expect(screen.getAllByText(/Anything to change\?/)).toHaveLength(2), { timeout: 3000 });
    expect(chat.calls).toEqual(["Say ok."]);
    expect(screen.queryByTestId("failed-reply")).toBeNull();
    // The failed exchange was replaced, not duplicated.
    expect(screen.getAllByText("Say ok.")).toHaveLength(1);
  });

  it("a cancelled request leaves no error bubble", async () => {
    await mountPlayground();
    await send("hello");
    await screen.findByText(/Anything to change\?/);
    chat.impl = async () => {
      throw Object.assign(new Error("Cancelled"), { failureClass: "aborted" });
    };
    await send("again");
    await waitFor(() => expect(chat.calls).toContain("again"));
    expect(screen.queryByTestId("failed-reply")).toBeNull();
  });
});
