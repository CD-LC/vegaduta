// The workbench playground (AgentPlayground), rendered with its real MessageList, composer, header, drawer and
// approval cards: the "Run settings" drawer opens from the header and returns focus; live status chips follow
// the turn (model call, tool, retry, backup model, waiting for approval); Stop cancels a running reply; a reply
// cut off by the output limit offers Continue, which sends a continuation; a step-limit stop links an editor to
// the setting and tells anyone else who to ask. Only the API clients and heavy sibling panels are mocked.

import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { runRequestLifecycle } from "../../lib/requestLifecycle";

type Steps = { onEvent: (event: Record<string, unknown>) => void };
type Script = (io: { onChunk: (c: string) => void; steps: Steps; signal: AbortSignal; message: string }) => Promise<void>;

const edge = vi.hoisted(() => ({ on: false }));
const chat = vi.hoisted(() => ({ script: null as null | Script, calls: [] as string[], toolNames: [] as unknown[] }));
const net = vi.hoisted(() => ({ pending: [] as unknown[] }));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));
vi.mock("../../lib/api", () => ({
  api: {
    streamChat: vi.fn(
      (
        _agentId: string,
        message: string,
        _sessionId: unknown,
        onChunk: (c: string) => void,
        _attachment: unknown,
        _turnRef: unknown,
        _attachments: unknown,
        _vision: unknown,
        lifecycle: { onController?: (c: unknown) => void } | undefined,
        steps: Steps,
        _pathOverride?: string,
        toolNames?: string[] | null
      ) => {
        chat.calls.push(message);
        chat.toolNames.push(toolNames);
        const controller = runRequestLifecycle<string | null>(
          async (ctx) => {
            ctx.markConnected();
            if (chat.script) await chat.script({ onChunk, steps, signal: ctx.signal, message });
            else onChunk("ok");
            return "session-1";
          },
          { target: "playground-test", surface: "chat-stream", retryPolicy: false, circuitBreaker: false }
        );
        lifecycle?.onController?.(controller);
        return controller.done.then((result) => {
          if (!result.ok) throw Object.assign(new Error("Cancelled"), { failureClass: "aborted" });
          return result.value;
        });
      }
    ),
    listApprovals: vi.fn(async () => net.pending),
    getApproval: vi.fn(async () => null),
    approveRequest: vi.fn(),
    rejectRequest: vi.fn(),
    listToolCalls: vi.fn(async () => []),
    getAgentSessionLlmUsage: vi.fn(async () => []),
    getAgentSessionLlmRequests: vi.fn(async () => []),
    getTenantSettings: vi.fn(async () => ({})),
    listProviders: vi.fn(async () => []),
    listProviderModels: vi.fn(async () => []),
    listCollections: vi.fn(async () => []),
    listFaceEnrollments: vi.fn(async () => []),
    listAvailableTools: vi.fn(async () => [{ name: "web_search", description: "Search the web" }]),
    setAgentToolAccess: vi.fn(async () => ({})),
    previewAgentPrompt: vi.fn(async () => ({})),
  },
}));
vi.mock("../../lib/api/agentRuntimeLimits", () => ({
  agentRuntimeLimitsApi: {
    getAgentRuntimeLimits: vi.fn(async () => ({
      overrides: {},
      effective: { maxToolSteps: 25, layers: { maxToolSteps: "PLATFORM" } },
      bounds: {},
    })),
  },
}));
vi.mock("../../lib/api/assistant", () => ({ assistantApi: { getChatTranscript: vi.fn(async () => ({ messages: [] })) } }));
vi.mock("../../lib/edge/capabilities", async (importOriginal) => {
  const real = await importOriginal<typeof import("../../lib/edge/capabilities")>();
  return { ...real, edgeEnabled: () => edge.on, resolveAutomaticEdgeFallback: vi.fn(async () => true) };
});
vi.mock("../../lib/edge/tierRouter", () => ({
  routeTurn: vi.fn(async () => ({ handled: false, reason: "all-tiers-failed" })),
  resetEdgeSession: vi.fn(),
}));
vi.mock("./IntelligenceSourceControl", () => ({ default: (): null => null, getEffectiveEdgeOptIn: async () => true }));
vi.mock("./CallPanel", () => ({ default: (): null => null }));
vi.mock("./CameraPanel", () => ({ default: (): null => null }));
vi.mock("./ShareAgentPanel", () => ({ default: (): null => null }));
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
vi.mock("../../lib/edge/tier1", () => ({ prewarmTier1Engine: vi.fn(), getTier1RunModel: vi.fn(() => null) }));
vi.mock("../../lib/edge/tier0", () => ({ tier0Available: vi.fn(async () => false) }));

const AGENT = { id: "agent-1", name: "mailer", providerName: "OPENAI", model: "gpt-4o-mini" };
const VIEWER = { ...AGENT, accessLevel: "VIEWER" };

beforeAll(() => {
  HTMLMediaElement.prototype.pause = vi.fn();
  HTMLMediaElement.prototype.load = vi.fn();
  Element.prototype.scrollTo = vi.fn();
  Element.prototype.scrollIntoView = vi.fn();
});
beforeEach(() => {
  vi.clearAllMocks();
  window.localStorage.clear();
  edge.on = false;
  chat.script = null;
  chat.calls.length = 0;
  chat.toolNames.length = 0;
  net.pending = [];
});
afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

const composer = () => screen.getByPlaceholderText("sendPlaceholder") as HTMLTextAreaElement;

async function mount(agent: Record<string, unknown> = AGENT) {
  const { default: AgentPlayground } = await import("./AgentPlayground");
  return render(<AgentPlayground agent={agent as never} />);
}

async function send(text: string) {
  fireEvent.change(composer(), { target: { value: text } });
  await act(async () => {
    fireEvent.keyDown(composer(), { key: "Enter" });
  });
}

/** A turn that stays open until the test lets it finish, so the live UI can be inspected mid-turn. */
function openTurn() {
  let finish!: () => void;
  const gate = new Promise<void>((resolve) => {
    finish = resolve;
  });
  let emit!: Steps["onEvent"];
  let write!: (c: string) => void;
  chat.script = async ({ onChunk, steps, signal }) => {
    emit = (e) => act(() => steps.onEvent(e));
    write = (c) => act(() => onChunk(c));
    await Promise.race([
      gate,
      new Promise<void>((_, reject) => signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true })),
    ]);
  };
  return { finish: () => act(async () => finish()), emit: (e: Record<string, unknown>) => emit(e), write: (c: string) => write(c) };
}

describe("AgentPlayground - Run settings drawer", () => {
  it("opens from the header, labelled and focused, and Escape closes it and returns focus to the button", async () => {
    await mount();
    expect(screen.queryByTestId("run-settings-drawer")).toBeNull();
    const button = screen.getByTestId("run-settings-button");
    expect(button.getAttribute("aria-expanded")).toBe("false");
    button.focus();
    fireEvent.click(button);
    const drawer = await screen.findByTestId("run-settings-drawer");
    expect(button.getAttribute("aria-expanded")).toBe("true");
    expect(drawer.getAttribute("role")).toBe("dialog");
    expect(document.activeElement).toBe(within(drawer).getByRole("heading", { name: "rsTitle" }));
    expect(within(drawer).getByTestId("run-settings-model")).toBeTruthy();
    fireEvent.keyDown(drawer, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("run-settings-drawer")).toBeNull());
    expect(document.activeElement).toBe(button);
  });

  it("makes no extra requests while it is closed", async () => {
    const { api } = await import("../../lib/api");
    await mount();
    expect(api.listAvailableTools).not.toHaveBeenCalled();
    expect(api.previewAgentPrompt).not.toHaveBeenCalled();
  });

  it("can start open, and closes when another agent is selected", async () => {
    const { default: AgentPlayground } = await import("./AgentPlayground");
    const view = render(<AgentPlayground agent={AGENT as never} initialRunSettingsOpen />);
    expect(await screen.findByTestId("run-settings-drawer")).toBeTruthy();
    view.rerender(<AgentPlayground agent={{ ...AGENT, id: "agent-2", name: "other" } as never} initialRunSettingsOpen />);
    await waitFor(() => expect(screen.queryByTestId("run-settings-drawer")).toBeNull());
  });

  describe("tool switches are for this run only", () => {
    const TWO_TOOLS = [
      { name: "web_search", description: "Search the web" },
      { name: "send_email", description: "Send an email" },
    ];

    async function withTwoTools(run: () => Promise<void>) {
      const { api } = await import("../../lib/api");
      const original = (api.listAvailableTools as ReturnType<typeof vi.fn>).getMockImplementation();
      (api.listAvailableTools as ReturnType<typeof vi.fn>).mockResolvedValue(TWO_TOOLS);
      try {
        await run();
      } finally {
        (api.listAvailableTools as ReturnType<typeof vi.fn>).mockImplementation(
          original ?? (async () => [{ name: "web_search", description: "Search the web" }])
        );
      }
    }

    it("sends the narrowed tool set with the message and never writes the agent", async () => {
      const { api } = await import("../../lib/api");
      await withTwoTools(async () => {
        await mount({ ...AGENT, allowedToolNames: [] });
        fireEvent.click(screen.getByTestId("run-settings-button"));
        fireEvent.click(await screen.findByLabelText("send_email"));
        expect(screen.getByTestId("rs-tools-run-only").textContent).toBe("rsToolsRunOnly");
        await send("hello");
        await waitFor(() => expect(chat.calls).toEqual(["hello"]));
        expect(chat.toolNames).toEqual([["web_search"]]);
        expect(api.setAgentToolAccess).not.toHaveBeenCalled();
      });
    });

    it("sends no selection until a switch is flipped", async () => {
      await mount();
      await send("hello");
      await waitFor(() => expect(chat.calls).toEqual(["hello"]));
      expect(chat.toolNames).toEqual([null]);
    });

    it("keeps the selection when the drawer is closed and reopened, and says so in the header", async () => {
      await withTwoTools(async () => {
        await mount({ ...AGENT, allowedToolNames: [] });
        expect(screen.queryByTestId("run-tools-limited")).toBeNull();
        fireEvent.click(screen.getByTestId("run-settings-button"));
        fireEvent.click(await screen.findByLabelText("send_email"));
        expect(await screen.findByTestId("run-tools-limited")).toBeTruthy();
        fireEvent.click(screen.getByTestId("rs-close"));
        await waitFor(() => expect(screen.queryByTestId("run-settings-drawer")).toBeNull());
        expect(screen.getByTestId("run-tools-limited")).toBeTruthy();
        fireEvent.click(screen.getByTestId("run-settings-button"));
        expect((await screen.findByLabelText("send_email")).getAttribute("aria-checked")).toBe("false");
        await send("again");
        await waitFor(() => expect(chat.toolNames).toEqual([["web_search"]]));
      });
    });

    it("reset goes back to the agent's own tools: the next message carries no selection", async () => {
      await withTwoTools(async () => {
        await mount({ ...AGENT, allowedToolNames: [] });
        fireEvent.click(screen.getByTestId("run-settings-button"));
        fireEvent.click(await screen.findByLabelText("send_email"));
        fireEvent.click(await screen.findByTestId("rs-tools-reset"));
        await waitFor(() => expect(screen.queryByTestId("run-tools-limited")).toBeNull());
        await send("hello");
        await waitFor(() => expect(chat.calls).toEqual(["hello"]));
        expect(chat.toolNames).toEqual([null]);
      });
    });

    it("a selection made for one agent never rides a message to another", async () => {
      await withTwoTools(async () => {
        const { default: AgentPlayground } = await import("./AgentPlayground");
        const view = render(<AgentPlayground agent={{ ...AGENT, allowedToolNames: [] } as never} />);
        fireEvent.click(screen.getByTestId("run-settings-button"));
        fireEvent.click(await screen.findByLabelText("send_email"));
        await screen.findByTestId("run-tools-limited");
        view.rerender(<AgentPlayground agent={{ ...AGENT, id: "agent-2", name: "other" } as never} />);
        await waitFor(() => expect(screen.queryByTestId("run-tools-limited")).toBeNull());
        await send("hello");
        await waitFor(() => expect(chat.calls).toEqual(["hello"]));
        expect(chat.toolNames).toEqual([null]);
      });
    });
  });

  it("is disabled until an agent is selected", async () => {
    const { default: AgentPlayground } = await import("./AgentPlayground");
    render(<AgentPlayground agent={null} />);
    expect((screen.getByTestId("run-settings-button") as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("AgentPlayground - live status chips", () => {
  it("follows the turn: preparing, model call, a tool, a retry, a backup model, then nothing", async () => {
    const turn = openTurn();
    await mount();
    await send("find the report");
    expect(await screen.findByTestId("chip-preparing")).toBeTruthy();

    turn.emit({ type: "phase", id: 1, phase: "model" });
    expect(screen.getByTestId("chip-modelCall")).toBeTruthy();

    turn.emit({ type: "tool_call", id: 2, tool: "web_search", args: "{}" });
    expect(screen.getByTestId("chip-tool").textContent).toBe('chipTool:{"tool":"web_search"}');

    turn.emit({ type: "tool_result", id: 2, tool: "web_search", preview: "ok" });
    turn.emit({ type: "retry", id: 3, attempt: 2, maxAttempts: 3, reason: "rate_limited" });
    expect(screen.getByTestId("chip-retrying").textContent).toBe(
      'chipRetryingAttemptReason:{"attempt":2,"max":3,"reason":"reasonRateLimited"}'
    );

    turn.emit({ type: "failover", id: 4, toModel: "gpt-4o", reason: "timeout" });
    expect(screen.getByTestId("chip-failover").textContent).toBe(
      'chipFailoverToReason:{"model":"gpt-4o","reason":"reasonTimeout"}'
    );

    turn.write("Found it.");
    expect(screen.getByTestId("chip-writing")).toBeTruthy();

    await turn.finish();
    await screen.findByText("Found it.");
    await waitFor(() => expect(screen.queryByTestId("live-status")?.textContent).toBe(""));
  });

  it("announces changes politely through one live region", async () => {
    await mount();
    const region = screen.getByTestId("live-status");
    expect(region.getAttribute("role")).toBe("status");
    expect(region.getAttribute("aria-live")).toBe("polite");
  });

  it("shows a chip for a tool call waiting for a person's decision", async () => {
    net.pending = [
      {
        id: "req-1",
        actionType: "mcp_tool.send_email",
        summary: "Tool call: send_email",
        status: "PENDING",
        requestedBy: "agent:agent-1:session:session-1",
        payload: JSON.stringify({ replay: { version: 1 }, arguments: { to: "bob@example.com" } }),
        createdAt: "2026-10-03T10:00:00Z",
      },
    ];
    await mount();
    await send("email bob");
    await screen.findByTestId("tool-approval-req-1");
    expect((await screen.findByTestId("chip-waitingApproval")).textContent).toBe('chipWaitingApproval:{"count":1}');
  });
});

describe("AgentPlayground - stop", () => {
  it("swaps Send for a labelled Stop while a reply runs, and stops it cleanly", async () => {
    openTurn();
    await mount();
    expect(screen.queryByTestId("composer-stop")).toBeNull();
    await send("write a long essay");
    const stop = await screen.findByTestId("composer-stop");
    expect(stop.getAttribute("aria-label")).toBe("stopRun");
    await act(async () => {
      fireEvent.click(stop);
    });
    await waitFor(() => expect(screen.queryByTestId("composer-stop")).toBeNull());
    // Cancelled on purpose: no error bubble, and the composer is free again.
    expect(screen.queryByTestId("failed-reply")).toBeNull();
    await waitFor(() => expect(composer().disabled).toBe(false));
  });
});

describe("AgentPlayground - how a reply ended", () => {
  it("offers Continue for a reply cut off by the output limit, and Continue sends a continuation", async () => {
    chat.script = async ({ onChunk, steps }) => {
      onChunk("The first half of the ans");
      steps.onEvent({ type: "finish", id: 9, finishReason: "length" });
    };
    await mount();
    await send("explain it");
    const notice = await screen.findByTestId("turn-outcome-truncated");
    expect(notice.textContent).toContain("outcomeTruncated");
    const next = chat.script;
    chat.script = async ({ onChunk }) => {
      onChunk(" and the second half.");
    };
    await act(async () => {
      fireEvent.click(within(notice).getByTestId("outcome-continue"));
    });
    await screen.findByText(/and the second half\./);
    expect(chat.calls).toEqual(["explain it", "continueMessage"]);
    expect(next).toBeTruthy();
  });

  it("recognises a reply core marked as interrupted even without a finish event", async () => {
    chat.script = async ({ onChunk }) => {
      onChunk("Half an answer\n\n[reply interrupted]");
    };
    await mount();
    await send("go");
    expect(await screen.findByTestId("turn-outcome-truncated")).toBeTruthy();
  });

  it("says plainly that the provider's safety system stopped a reply", async () => {
    chat.script = async ({ onChunk, steps }) => {
      onChunk("I can't help with that.");
      steps.onEvent({ type: "finish", id: 9, finishReason: "content_filter" });
    };
    await mount();
    await send("go");
    const notice = await screen.findByTestId("turn-outcome-refused");
    expect(notice.textContent).toContain('outcomeRefusedReason:{"reason":"content filter"}');
    expect(within(notice).queryByTestId("outcome-continue")).toBeNull();
  });

  it("links an editor to the tool-call limit when a run hit it", async () => {
    chat.script = async ({ onChunk, steps }) => {
      onChunk("I got most of the way.");
      steps.onEvent({ type: "guardrail", id: 5, reason: "exceeded the maximum of 25 tool calls for a single turn" });
    };
    await mount();
    await send("do everything");
    const link = await screen.findByTestId("stop-limit-raise");
    expect(link.getAttribute("href")).toBe(
      "/agents?agentId=agent-1&view=workbench&section=behavior&focus=agent.limits.maxToolSteps"
    );
    expect(link.textContent).toBe("stopRaiseSteps");
  });

  it("tells someone who cannot edit the agent to ask an editor instead", async () => {
    chat.script = async ({ onChunk, steps }) => {
      onChunk("I got most of the way.");
      steps.onEvent({ type: "guardrail", id: 5, reason: "exceeded the maximum of 25 tool calls for a single turn" });
    };
    await mount(VIEWER);
    await send("do everything");
    expect(await screen.findByTestId("stop-limit-ask")).toBeTruthy();
    expect(screen.queryByTestId("stop-limit-raise")).toBeNull();
  });
});
