import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

// The checklist must NOT draft through api.askAiDraft (its toJson raises a
// global error toast on every failure). It goes through quietDraft.ts, which
// has its own test file; here it is a mock that resolves text or null.
const askAiDraft = vi.fn();
const requestDraftQuietly = vi.fn();
const getChannelsStatus = vi.fn();
vi.mock("../../../lib/api", () => ({
  api: {
    askAiDraft: (...args: unknown[]) => askAiDraft(...args),
    getChannelsStatus: (...args: unknown[]) => getChannelsStatus(...args),
  },
}));
vi.mock("./quietDraft", () => ({
  requestDraftQuietly: (...args: unknown[]) => requestDraftQuietly(...args),
}));

import CreateAgentChecklist, { type CreateAgentChecklistProps } from "./CreateAgentChecklist";
import { DEFAULT_DRAFT_PROMPT } from "./agentChecklist";

function baseProps(over: Partial<CreateAgentChecklistProps> = {}): CreateAgentChecklistProps {
  return {
    isDraft: true,
    agentId: null,
    name: "",
    systemPrompt: DEFAULT_DRAFT_PROMPT,
    mode: "SIMPLE",
    knowledgeBaseEnabled: false,
    collectionCount: 0,
    allowedCollectionCount: 0,
    allowedToolCount: 0,
    templateKey: null,
    totalAgents: 0,
    saving: false,
    onSave: vi.fn(),
    onApplyDraft: vi.fn(),
    onPickStarter: vi.fn(),
    ...over,
  };
}

const savedProps = { isDraft: false, agentId: "a1", name: "Shop", systemPrompt: "x", totalAgents: 1 };

describe("CreateAgentChecklist", () => {
  beforeEach(() => {
    askAiDraft.mockReset();
    requestDraftQuietly.mockReset();
    getChannelsStatus.mockReset();
    window.localStorage.clear();
    Element.prototype.scrollIntoView = vi.fn();
  });
  afterEach(() => cleanup());

  it("makes no claim about the agent count or API keys, and holds the starting points back when the list has not loaded", () => {
    const { container, rerender } = render(<CreateAgentChecklist {...baseProps()} />);
    // An empty list can be a failed load (agents/page.tsx swallows it).
    expect(screen.getByText("Pick a way to start")).toBeTruthy();
    expect(container.textContent).not.toMatch(/no agents yet/i);
    // The drafting endpoint fails for grant tenants without a key.
    expect(container.textContent).not.toMatch(/API key/i);
    rerender(<CreateAgentChecklist {...baseProps({ agentsLoaded: false })} />);
    expect(screen.queryByTestId("agent-starting-points")).toBeNull();
    rerender(<CreateAgentChecklist {...baseProps({ agentsLoaded: true })} />);
    expect(screen.getByTestId("agent-starting-points")).toBeTruthy();
  });

  it("an untouched draft shows no progress and the tools step as Automatic, not Done", () => {
    render(<CreateAgentChecklist {...baseProps()} />);
    expect(screen.getByText(/^0 of 4 done/)).toBeTruthy();
    expect(screen.getByText("Automatic")).toBeTruthy();
    expect(screen.queryByText("Done")).toBeNull();
  });

  it("empty agent list + untouched draft: shows the three starting points and all five steps", () => {
    render(<CreateAgentChecklist {...baseProps()} />);
    expect(screen.getByTestId("agent-starting-points")).toBeTruthy();
    expect(screen.getByText("Start from a template")).toBeTruthy();
    expect(screen.getByText("Describe it in a sentence")).toBeTruthy();
    expect(screen.getByText("Open the sandbox")).toBeTruthy();
    expect(screen.getByText(/1\. What should it do\?/)).toBeTruthy();
    expect(screen.getByText(/5\. Put it on a channel/)).toBeTruthy();
    // No channel read for a draft - nothing to check yet.
    expect(getChannelsStatus).not.toHaveBeenCalled();
  });

  it("picking a starter hands it to the workbench", () => {
    const onPickStarter = vi.fn();
    render(<CreateAgentChecklist {...baseProps({ onPickStarter })} />);
    fireEvent.click(screen.getByText("Kirana store"));
    expect(onPickStarter).toHaveBeenCalledTimes(1);
    expect(onPickStarter.mock.calls[0][0].key).toBe("starter:kirana");
  });

  it("the data-analysis card hands its template key to the workbench and promises no file upload", () => {
    // Not offered at all unless the workbench can apply a console template.
    const { unmount } = render(<CreateAgentChecklist {...baseProps()} />);
    expect(screen.queryByTestId("starting-point-data-analyst")).toBeNull();
    unmount();

    const onPickTemplate = vi.fn();
    render(<CreateAgentChecklist {...baseProps({ onPickTemplate })} />);
    const card = screen.getByTestId("starting-point-data-analyst");
    // Attaching files / charts / workbooks need a default-off core option.
    expect(card.textContent).not.toMatch(/attach|excel|csv|chart|workbook/i);
    fireEvent.click(screen.getByText("Use the data analyst"));
    fireEvent.click(screen.getByText("Use the data analyst"));
    // A button, not a same-URL link: every click reaches the workbench.
    expect(onPickTemplate).toHaveBeenCalledTimes(2);
    expect(onPickTemplate).toHaveBeenCalledWith("data-analyst");
  });

  it("hides the starting points once the person has typed", () => {
    render(<CreateAgentChecklist {...baseProps({ name: "Shop" })} />);
    expect(screen.queryByTestId("agent-starting-points")).toBeNull();
  });

  it("refuses a too-short sentence in words, without calling the backend", () => {
    render(<CreateAgentChecklist {...baseProps()} />);
    fireEvent.click(screen.getByText("Draft my agent"));
    expect(screen.getByRole("alert").textContent).toMatch(/few more words/);
    expect(requestDraftQuietly).not.toHaveBeenCalled();
  });

  it("drafts through the quiet call (never api.askAiDraft, which toasts), previews, and applies only on Use this", async () => {
    requestDraftQuietly.mockResolvedValue("NAME: Cake Desk\n\nYou are the bakery assistant.");
    const onApplyDraft = vi.fn();
    render(<CreateAgentChecklist {...baseProps({ onApplyDraft })} />);
    fireEvent.change(screen.getByLabelText("Describe it in one sentence"), {
      target: { value: "Take cake orders for my bakery" },
    });
    fireEvent.click(screen.getByText("Draft my agent"));
    await waitFor(() => expect(screen.getByText("Cake Desk")).toBeTruthy());
    expect(requestDraftQuietly).toHaveBeenCalledWith(
      expect.objectContaining({ fieldLabel: "Agent system prompt" }),
      expect.objectContaining({ timeoutMs: 40_000, signal: expect.any(AbortSignal) })
    );
    expect(askAiDraft).not.toHaveBeenCalled();
    expect(screen.queryByTestId("basic-draft-note")).toBeNull();
    expect(onApplyDraft).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Use this"));
    expect(onApplyDraft).toHaveBeenCalledWith({ name: "Cake Desk", systemPrompt: "You are the bakery assistant." });
  });

  it("any miss (no provider, quota, offline, timeout -> null) gives the basic draft with one calm line and no retry promise", async () => {
    requestDraftQuietly.mockResolvedValue(null);
    const onApplyDraft = vi.fn();
    render(<CreateAgentChecklist {...baseProps({ onApplyDraft })} />);
    fireEvent.change(screen.getByLabelText("Describe it in one sentence"), {
      target: { value: "Answer questions about my salon" },
    });
    fireEvent.click(screen.getByText("Draft my agent"));
    const note = await screen.findByTestId("basic-draft-note");
    expect(note.textContent).toMatch(/We made a basic draft from your sentence/);
    // A retry cannot succeed for a workspace with no provider - never suggest one.
    expect(note.textContent).not.toMatch(/retry|try again|did not answer/i);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(askAiDraft).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Use this"));
    expect(onApplyDraft.mock.calls[0][0].systemPrompt).toContain("Answer questions about my salon");
    expect(onApplyDraft.mock.calls[0][0].name.length).toBeGreaterThan(0);
  });

  it("even a quiet call that breaks its never-throws contract ends in the basic draft, not a stuck spinner", async () => {
    requestDraftQuietly.mockRejectedValue(new Error("boom"));
    render(<CreateAgentChecklist {...baseProps()} />);
    fireEvent.change(screen.getByLabelText("Describe it in one sentence"), {
      target: { value: "Answer questions about my salon" },
    });
    fireEvent.click(screen.getByText("Draft my agent"));
    expect(await screen.findByTestId("basic-draft-note")).toBeTruthy();
    expect(screen.queryByText("Drafting…")).toBeNull();
  });

  it("leaving the page cancels the in-flight draft request", () => {
    requestDraftQuietly.mockReturnValue(new Promise(() => undefined));
    const { unmount } = render(<CreateAgentChecklist {...baseProps()} />);
    fireEvent.change(screen.getByLabelText("Describe it in one sentence"), {
      target: { value: "Answer questions about my salon" },
    });
    fireEvent.click(screen.getByText("Draft my agent"));
    const signal = requestDraftQuietly.mock.calls[0][1].signal as AbortSignal;
    expect(signal.aborted).toBe(false);
    unmount();
    expect(signal.aborted).toBe(true);
  });

  it("save-gated steps are disabled until step 1 is done, then call onSave", () => {
    const onSave = vi.fn();
    const { rerender } = render(<CreateAgentChecklist {...baseProps({ onSave, totalAgents: 1 })} />);
    const gated = screen.getAllByText("Save draft").map((el) => el.closest("button") as HTMLButtonElement);
    expect(gated.length).toBeGreaterThan(0);
    expect(gated.every((b) => b.disabled)).toBe(true);

    rerender(
      <CreateAgentChecklist {...baseProps({ onSave, totalAgents: 1, name: "Shop", systemPrompt: "You help the shop." })} />
    );
    const first = screen.getAllByText("Save draft")[0].closest("button") as HTMLButtonElement;
    expect(first.disabled).toBe(false);
    fireEvent.click(first);
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("saved agent: a failed channel read renders Not checked, never a tick or a to-do", async () => {
    getChannelsStatus.mockRejectedValue(new Error("403"));
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await waitFor(() => expect(getChannelsStatus).toHaveBeenCalledTimes(1));
    expect(screen.getByText("Not checked")).toBeTruthy();
  });

  it("saved agent: ticks the channel step when the channel answers with THIS agent, and the test step from a real reply", async () => {
    getChannelsStatus.mockResolvedValue({ channels: [{ label: "WhatsApp", state: "CONNECTED", defaultAgentId: "a1" }] });
    window.localStorage.setItem(
      "agentic:playground:a1",
      JSON.stringify({
        sessionId: "s",
        turns: [
          { role: "user", content: "hi" },
          { role: "assistant", content: "Hello! How can I help?", pending: false },
        ],
        savedAt: Date.now(),
      })
    );
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await waitFor(() => expect(screen.getByText("WhatsApp answers with this agent.")).toBeTruthy());
    expect(screen.getByText("This agent has replied to you in the Playground.")).toBeTruthy();
    expect(window.localStorage.getItem("vd_agent_tested_v1:a1")).toBe("1");
  });

  it("saved agent: a channel bound to a different agent is a to-do, not a tick", async () => {
    getChannelsStatus.mockResolvedValue({ channels: [{ label: "WhatsApp", state: "CONNECTED", defaultAgentId: "someone-else" }] });
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await waitFor(() => expect(screen.getByText(/WhatsApp is connected but answers with another agent/)).toBeTruthy());
    expect(screen.queryByText(/Nothing left that needs you/)).toBeNull();
  });

  it("a transcript with only a failed user turn (or an empty/pending reply) never sets the permanent tested tick", async () => {
    getChannelsStatus.mockResolvedValue({ channels: [] });
    window.localStorage.setItem(
      "agentic:playground:a1",
      JSON.stringify({
        sessionId: "s",
        turns: [
          { role: "user", content: "hi" },
          { role: "assistant", content: "", pending: true },
          { role: "assistant", content: "   " },
        ],
        savedAt: Date.now(),
      })
    );
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await waitFor(() => expect(getChannelsStatus).toHaveBeenCalled());
    expect(screen.getByText("Send it the kind of message a customer would send.")).toBeTruthy();
    expect(window.localStorage.getItem("vd_agent_tested_v1:a1")).toBeNull();
  });

  it("ticks Test it at once on the playground's vd:playground-turn event, for this agent only", async () => {
    getChannelsStatus.mockResolvedValue({ channels: [] });
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await waitFor(() => expect(getChannelsStatus).toHaveBeenCalled());
    act(() => {
      window.dispatchEvent(new CustomEvent("vd:playground-turn", { detail: { agentId: "other" } }));
      window.dispatchEvent(new CustomEvent("vd:playground-turn"));
    });
    expect(screen.getByText("Send it the kind of message a customer would send.")).toBeTruthy();
    act(() => {
      window.dispatchEvent(new CustomEvent("vd:playground-turn", { detail: { agentId: "a1" } }));
    });
    expect(screen.getByText("This agent has replied to you in the Playground.")).toBeTruthy();
    expect(window.localStorage.getItem("vd_agent_tested_v1:a1")).toBe("1");
  });

  it("still ticks Test it from the localStorage poll when no event is dispatched", async () => {
    vi.useFakeTimers();
    try {
      getChannelsStatus.mockResolvedValue({ channels: [] });
      render(<CreateAgentChecklist {...baseProps(savedProps)} />);
      window.localStorage.setItem(
        "agentic:playground:a1",
        JSON.stringify({ sessionId: "s", turns: [{ role: "assistant", content: "Hello" }], savedAt: Date.now() })
      );
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4100);
      });
      expect(screen.getByText("This agent has replied to you in the Playground.")).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });

  it("a button whose target is missing from the page says so instead of doing nothing", async () => {
    getChannelsStatus.mockResolvedValue({ channels: [] });
    render(<CreateAgentChecklist {...baseProps(savedProps)} />);
    await act(async () => {
      fireEvent.click(screen.getByText("Open the Playground"));
      await new Promise((r) => setTimeout(r, 60));
    });
    expect(screen.getByRole("status").textContent).toMatch(/could not find that part of the page/);
  });

  it("collapses to one line with a next-step button, and remembers the choice", () => {
    render(<CreateAgentChecklist {...baseProps({ totalAgents: 1 })} />);
    fireEvent.click(screen.getByText("Hide"));
    expect(screen.queryByText(/5\. Put it on a channel/)).toBeNull();
    expect(screen.getByText("Describe it")).toBeTruthy();
    expect(window.localStorage.getItem("vd_agent_checklist_collapsed_v1")).toBe("1");
  });

  it("opens the plain-language word list", () => {
    render(<CreateAgentChecklist {...baseProps()} />);
    fireEvent.click(screen.getByText("What do the words on this page mean?"));
    expect(screen.getByText("Temperature")).toBeTruthy();
    expect(screen.getByText(/shown as "Persona · System prompt"/)).toBeTruthy();
  });
});
