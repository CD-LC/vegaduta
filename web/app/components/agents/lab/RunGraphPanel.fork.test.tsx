// Run Graph panel, fork flow - above all its ORDER: probe, then plan, then snapshot, then rewind,
// then the re-send, and no rewind at all when the plan is refused. Shared fixtures:
// runGraphTestKit.tsx.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { choose } from "./selectTestUtils";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const probeSessionEntryCount = vi.hoisted(() => vi.fn());
const rewindChatSession = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock("./labApi", () => ({
  LAB_MAX_MESSAGE_CHARS: 8000,
  labApi: {
    probeSessionEntryCount: (...a: unknown[]) => probeSessionEntryCount(...a),
    rewindChatSession: (...a: unknown[]) => rewindChatSession(...a),
  },
}));
vi.mock("./RunGraphCanvas", async () => ({
  default: (await import("./runGraphTestKit")).MockRunGraphCanvas,
}));

import RunGraphPanel from "./RunGraphPanel";
import { clearMemorySnapshots, loadSnapshots } from "./forkSnapshots";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn } from "./types";
import { SESSION, STORE_KEY, cleanReply, followBox, inspector, openFork, props, turnSelect } from "./runGraphTestKit";

beforeEach(() => {
  window.localStorage.clear();
  clearMemorySnapshots();
  probeSessionEntryCount.mockReset();
  rewindChatSession.mockReset();
});

describe("RunGraphPanel - fork", () => {
  it("probes, snapshots, rewinds and re-sends, in that order", async () => {
    const order: string[] = [];
    const onForkRerun = vi.fn(() => order.push("rerun"));
    probeSessionEntryCount.mockImplementation(async () => {
      order.push("probe");
      return 4;
    });
    rewindChatSession.mockImplementation(async () => {
      // The snapshot must already exist when the rewind goes out.
      order.push(`rewind:snapshots=${loadSnapshots(FIXTURE_AGENT.id).length}`);
      return { sessionId: SESSION, keepTurns: 2, droppedTurns: 2 };
    });
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    expect(dialog.getByText("graphForkTitle")).toBeInTheDocument();
    expect(dialog.getByText("graphForkBody")).toBeInTheDocument();
    expect(dialog.getByText("graphForkSideEffects")).toBeInTheDocument();
    expect(dialog.getByText("graphForkEstimate")).toBeInTheDocument();
    expect(dialog.queryByText("graphForkAttachmentNote")).not.toBeInTheDocument();
    expect(dialog.queryByText("graphForkCacheNote")).not.toBeInTheDocument();
    const message = dialog.getByLabelText("graphForkMessageLabel") as HTMLTextAreaElement;
    expect(message.value).toBe(FIXTURE_TURNS[2].content);
    expect(message).toHaveAttribute("maxlength", "8000");

    fireEvent.change(message, { target: { value: "  What are your weekend hours?  " } });
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(screen.getByText("graphForkChecking")).toBeInTheDocument();

    await waitFor(() => expect(onForkRerun).toHaveBeenCalledTimes(1));
    expect(order).toEqual(["probe", "rewind:snapshots=1", "rerun"]);
    expect(probeSessionEntryCount).toHaveBeenCalledWith(FIXTURE_AGENT.id, SESSION);
    expect(rewindChatSession).toHaveBeenCalledWith(FIXTURE_AGENT.id, SESSION, 2);
    // userIndex of the forked message, and the trimmed text.
    expect(onForkRerun).toHaveBeenCalledWith(2, "What are your weekend hours?");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    const [snapshot] = loadSnapshots(FIXTURE_AGENT.id);
    expect(snapshot).toMatchObject({
      sessionId: SESSION,
      forkUserIndex: 2,
      originalMessage: FIXTURE_TURNS[2].content,
      sentMessage: "What are your weekend hours?",
    });
    expect(snapshot.originalTail).toHaveLength(2);
  });

  it("forks an earlier exchange with keepTurns 0 and the whole tail in the snapshot", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockResolvedValue({ sessionId: SESSION, keepTurns: 0, droppedTurns: 4 });
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    choose(turnSelect(), 0);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(onForkRerun).toHaveBeenCalledWith(0, FIXTURE_TURNS[0].content));
    expect(rewindChatSession).toHaveBeenCalledWith(FIXTURE_AGENT.id, SESSION, 0);
    expect(loadSnapshots(FIXTURE_AGENT.id)[0].originalTail).toHaveLength(4);
    // Back to following the latest exchange (the branch).
    expect(followBox().checked).toBe(true);
  });

  it("refuses on a count mismatch: says so, sends no rewind, keeps no snapshot", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValue(5);
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent('graphForkMismatch:{"server":5,"local":4}');
    expect(rewindChatSession).not.toHaveBeenCalled();
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(window.localStorage.getItem(STORE_KEY)).toBeNull();
    expect(screen.queryByText("graphForkChecking")).not.toBeInTheDocument();
    // Still open; Cancel closes it.
    fireEvent.click(dialog.getByRole("button", { name: "cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("when the rewind keeps a different number than planned: fails, drops the snapshot, re-sends nothing", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockResolvedValue({ sessionId: SESSION, keepTurns: 1, droppedTurns: 0 });
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent(/^graphForkFailed:/);
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(loadSnapshots(FIXTURE_AGENT.id)).toEqual([]);
  });

  it("shows a thrown error's message, whether the probe or the rewind threw", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockRejectedValueOnce(new Error("Session not found."));
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent('graphForkFailed:{"reason":"Session not found."}');
    expect(rewindChatSession).not.toHaveBeenCalled();

    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockRejectedValue(new Error("Network down."));
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(dialog.getByRole("alert")).toHaveTextContent('graphForkFailed:{"reason":"Network down."}'));
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(loadSnapshots(FIXTURE_AGENT.id)).toEqual([]);
  });

  it("a rewind whose answer was lost but which DID cut the session: keeps the snapshot and carries on", async () => {
    const onForkRerun = vi.fn();
    // 4 entries before; the rewind throws; the session now holds the planned 2.
    probeSessionEntryCount.mockResolvedValueOnce(4).mockResolvedValueOnce(2);
    rewindChatSession.mockRejectedValue(new Error("Gateway timeout."));
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(onForkRerun).toHaveBeenCalledWith(2, FIXTURE_TURNS[2].content));
    expect(probeSessionEntryCount).toHaveBeenCalledTimes(2);
    expect(rewindChatSession).toHaveBeenCalledTimes(1);
    expect(loadSnapshots(FIXTURE_AGENT.id)).toHaveLength(1);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("a thrown rewind whose outcome cannot be established: keeps the snapshot, re-sends nothing, says the state is unknown", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValueOnce(4).mockRejectedValueOnce(new Error("Network down."));
    rewindChatSession.mockRejectedValue(new Error("Connection reset."));
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent('graphForkUnknown:{"reason":"Connection reset."}');
    expect(onForkRerun).not.toHaveBeenCalled();
    // The snapshot is the only record of what may have been cut.
    expect(loadSnapshots(FIXTURE_AGENT.id)).toHaveLength(1);
    fireEvent.click(dialog.getByRole("button", { name: "cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(within(screen.getByTestId("lab-graph-compare")).queryByText("graphCompareNone")).not.toBeInTheDocument();
  });

  it("a thrown rewind that left the session at some other length is also 'unknown', not 'nothing happened'", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValueOnce(4).mockResolvedValueOnce(3);
    rewindChatSession.mockRejectedValue(new Error("Bad gateway."));
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(await dialog.findByRole("alert")).toHaveTextContent(/^graphForkUnknown:/);
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(loadSnapshots(FIXTURE_AGENT.id)).toHaveLength(1);
  });

  it("a turn that starts while the rewind is out is not interleaved with: nothing is re-sent, the snapshot stays", async () => {
    const onForkRerun = vi.fn();
    let release: (value: { sessionId: string; keepTurns: number; droppedTurns: number }) => void = () => {};
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockReturnValue(new Promise((resolve) => (release = resolve)));
    const { rerender } = render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(rewindChatSession).toHaveBeenCalledTimes(1));
    // The camera watch loop fires a turn while the rewind request is out.
    rerender(
      <RunGraphPanel
        {...props({
          onForkRerun,
          busy: true,
          turns: [...FIXTURE_TURNS, { role: "user", content: "watch" }, { role: "assistant", content: "", ref: "w", pending: true }],
        })}
      />
    );
    release({ sessionId: SESSION, keepTurns: 2, droppedTurns: 2 });
    expect(await dialog.findByRole("alert")).toHaveTextContent("graphForkInterleaved");
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(loadSnapshots(FIXTURE_AGENT.id)).toHaveLength(1);
  });

  it("says so in the lab when the re-sent message gets no reply", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockResolvedValue({ sessionId: SESSION, keepTurns: 2, droppedTurns: 2 });
    const { rerender } = render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(onForkRerun).toHaveBeenCalledWith(2, FIXTURE_TURNS[2].content));
    const prefix = FIXTURE_TURNS.slice(0, 2);
    const resent: LabTurn = { role: "user", content: FIXTURE_TURNS[2].content };

    // The playground cuts the transcript and sends...
    rerender(<RunGraphPanel {...props({ onForkRerun, busy: true, turns: [...prefix, resent, { role: "assistant", content: "", ref: "p", pending: true }] })} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // ...the send fails before a token arrives, and the pending reply is withdrawn.
    rerender(<RunGraphPanel {...props({ onForkRerun, busy: false, turns: [...prefix, resent] })} />);
    expect(screen.getByRole("alert")).toHaveTextContent("graphForkResendFailed");
    expect(screen.queryByText("graphNoSteps")).not.toBeInTheDocument();
    expect(screen.queryByText("graphNoReply")).not.toBeInTheDocument();

    // A later message that is answered clears it.
    rerender(<RunGraphPanel {...props({ onForkRerun, turns: [...prefix, resent, { role: "user", content: "Again" }, cleanReply("Here.")] })} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("while the server is being checked, Tab goes nowhere: every control in the dialog is disabled", async () => {
    probeSessionEntryCount.mockReturnValue(new Promise<number>(() => {}));
    render(<RunGraphPanel {...props()} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    expect(screen.getByText("graphForkChecking")).toBeInTheDocument();
    expect(dialog.getByLabelText("graphForkMessageLabel")).toBeDisabled();
    for (const shiftKey of [false, true]) {
      const event = new KeyboardEvent("keydown", { key: "Tab", shiftKey, bubbles: true, cancelable: true });
      fireEvent(document.body, event);
      expect(event.defaultPrevented).toBe(true);
    }
  });

  it("cache guard: an unchanged message on a caching agent needs an explicit tick", () => {
    render(<RunGraphPanel {...props({ agent: { ...FIXTURE_AGENT, responseCachingEnabled: true } })} />);
    const dialog = openFork();
    const confirm = dialog.getByRole("button", { name: "graphForkConfirm" });
    expect(dialog.getByText("graphForkCacheNote")).toBeInTheDocument();
    expect(confirm).toBeDisabled();
    // Whitespace is not an edit.
    const message = dialog.getByLabelText("graphForkMessageLabel");
    fireEvent.change(message, { target: { value: `  ${FIXTURE_TURNS[2].content} ` } });
    expect(confirm).toBeDisabled();

    fireEvent.click(dialog.getByLabelText("graphForkCacheConfirm"));
    expect(confirm).toBeEnabled();
    fireEvent.click(dialog.getByLabelText("graphForkCacheConfirm"));
    expect(confirm).toBeDisabled();

    // An edited message needs no tick, and the checkbox goes away.
    fireEvent.change(message, { target: { value: "What are your weekend hours?" } });
    expect(confirm).toBeEnabled();
    expect(dialog.queryByLabelText("graphForkCacheConfirm")).not.toBeInTheDocument();
    expect(dialog.getByText("graphForkCacheNote")).toBeInTheDocument();
    expect(probeSessionEntryCount).not.toHaveBeenCalled();
  });

  it("cache guard also applies to semantic caching", () => {
    render(<RunGraphPanel {...props({ agent: { ...FIXTURE_AGENT, semanticResponseCachingEnabled: true } })} />);
    expect(openFork().getByRole("button", { name: "graphForkConfirm" })).toBeDisabled();
  });

  it("will not send an empty message", () => {
    render(<RunGraphPanel {...props()} />);
    const dialog = openFork();
    fireEvent.change(dialog.getByLabelText("graphForkMessageLabel"), { target: { value: "   " } });
    expect(dialog.getByRole("button", { name: "graphForkConfirm" })).toBeDisabled();
  });

  it("mentions attachments when the original message had one", () => {
    const turns: LabTurn[] = [{ role: "user", content: "Look", attachment: { name: "a.png" } }, cleanReply("Seen.")];
    render(<RunGraphPanel {...props({ turns })} />);
    expect(openFork().getByText("graphForkAttachmentNote")).toBeInTheDocument();
  });

  it("disables Fork with the reason: busy, no session, on-device turn, unclean tail", () => {
    const { rerender } = render(<RunGraphPanel {...props({ busy: true })} />);
    fireEvent.click(screen.getByTestId("node-u"));
    const fork = () => inspector().getByRole("button", { name: "graphFork" });
    expect(fork()).toBeDisabled();
    expect(inspector().getByText("graphForkBusy")).toBeInTheDocument();

    rerender(<RunGraphPanel {...props({ sessionId: null })} />);
    expect(fork()).toBeDisabled();
    expect(inspector().getByText("graphForkNoSession")).toBeInTheDocument();

    const onDevice: LabTurn[] = [...FIXTURE_TURNS.slice(0, 2), { role: "user", content: "2+2?" }, { role: "assistant", content: "4", onDevice: true }];
    rerender(<RunGraphPanel {...props({ turns: onDevice })} />);
    expect(fork()).toBeDisabled();
    expect(inspector().getByText("graphForkOnDevice")).toBeInTheDocument();

    // Exchange 1 is clean, but the on-device turn after it cannot be rewound.
    choose(turnSelect(), 0);
    fireEvent.click(screen.getByTestId("node-u"));
    expect(fork()).toBeDisabled();
    expect(inspector().getByText("graphForkTailUnclean")).toBeInTheDocument();

    const cached: LabTurn[] = [{ role: "user", content: "Hi" }, { role: "assistant", content: "Hello", ref: "cached" }, ...FIXTURE_TURNS.slice(2)];
    rerender(<RunGraphPanel {...props({ turns: cached })} />);
    choose(turnSelect(), 1);
    fireEvent.click(screen.getByTestId("node-u"));
    expect(fork()).toBeDisabled();
    expect(inspector().getByText("graphForkPrefixUnclean")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("re-checks the transcript on confirm: a turn that changed under the dialog is refused", async () => {
    const onForkRerun = vi.fn();
    let release: (count: number) => void = () => {};
    probeSessionEntryCount.mockReturnValue(new Promise<number>((resolve) => (release = resolve)));
    const { rerender } = render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    // While the probe is out, a new turn starts in the playground.
    rerender(
      <RunGraphPanel
        {...props({
          onForkRerun,
          turns: [...FIXTURE_TURNS, { role: "user", content: "More" }, { role: "assistant", content: "", ref: "p", pending: true }],
        })}
      />
    );
    release(4);
    expect(await dialog.findByRole("alert")).toHaveTextContent("graphForkTailUnclean");
    expect(rewindChatSession).not.toHaveBeenCalled();
    expect(onForkRerun).not.toHaveBeenCalled();
  });

  it("Escape closes the fork dialog and claims the key, so the lab stays open", () => {
    render(<RunGraphPanel {...props()} />);
    openFork();
    const event = new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true });
    fireEvent(document.body, event);
    expect(event.defaultPrevented).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("moves focus into the dialog and keeps Tab inside it", () => {
    render(<RunGraphPanel {...props()} />);
    const dialog = openFork();
    const message = dialog.getByLabelText("graphForkMessageLabel");
    const confirm = dialog.getByRole("button", { name: "graphForkConfirm" });
    expect(message).toHaveFocus();
    confirm.focus();
    fireEvent.keyDown(confirm, { key: "Tab" });
    expect(message).toHaveFocus();
    fireEvent.keyDown(message, { key: "Tab", shiftKey: true });
    expect(confirm).toHaveFocus();
  });

  it("gives focus back to the Fork button on Cancel", () => {
    render(<RunGraphPanel {...props()} />);
    fireEvent.click(screen.getByTestId("node-u"));
    const fork = inspector().getByRole("button", { name: "graphFork" });
    fork.focus();
    fireEvent.click(fork);
    const dialog = within(screen.getByRole("dialog"));
    expect(dialog.getByLabelText("graphForkMessageLabel")).toHaveFocus();
    fireEvent.click(dialog.getByRole("button", { name: "cancel" }));
    expect(fork).toHaveFocus();
  });

  it("after a fork, when the Fork button is gone, focus lands on the turn selector", async () => {
    const onForkRerun = vi.fn();
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockResolvedValue({ sessionId: SESSION, keepTurns: 2, droppedTurns: 2 });
    render(<RunGraphPanel {...props({ onForkRerun })} />);
    fireEvent.click(screen.getByTestId("node-u"));
    const fork = inspector().getByRole("button", { name: "graphFork" });
    fork.focus();
    fireEvent.click(fork);
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(turnSelect()).toHaveFocus();
  });
});
