// Run Graph panel, compare view: the stored original next to the live branch, and a snapshot
// that survives storage refusing it. Shared fixtures: runGraphTestKit.tsx.

import { beforeEach, describe, expect, it, vi } from "vitest";
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
import { clearMemorySnapshots, loadSnapshots, saveSnapshot } from "./forkSnapshots";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn } from "./types";
import { SESSION, STORE_KEY, cleanReply, openFork, props } from "./runGraphTestKit";

beforeEach(() => {
  window.localStorage.clear();
  clearMemorySnapshots();
  probeSessionEntryCount.mockReset();
  rewindChatSession.mockReset();
});

describe("RunGraphPanel - compare", () => {
  const SENT = "Where is order B-2?";
  const seed = (sessionId = SESSION) =>
    saveSnapshot(FIXTURE_AGENT.id, {
      sessionId,
      forkUserIndex: 0,
      originalMessage: FIXTURE_TURNS[0].content,
      sentMessage: SENT,
      originalTail: FIXTURE_TURNS.slice(0),
    }).snapshot;
  const branchTurns: LabTurn[] = [{ role: "user", content: SENT }, cleanReply("B-2 arrives tomorrow.")];
  const compare = () => within(screen.getByTestId("lab-graph-compare"));

  it("says there is nothing to compare when this session has no snapshot", () => {
    seed("some-other-session");
    render(<RunGraphPanel {...props()} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().getByText("graphCompareNone")).toBeInTheDocument();
    expect(compare().getByText("graphSnapshotNote")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-canvas")).not.toBeInTheDocument();
    fireEvent.click(compare().getByRole("button", { name: "graphCompareBack" }));
    expect(screen.getByLabelText("graphTurnLabel")).toBeInTheDocument();
  });

  it("shows the original next to the live branch, with what differs", () => {
    seed();
    render(<RunGraphPanel {...props({ turns: branchTurns })} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().getByRole("combobox").textContent).toContain("graphCompareSnapshotOption");
    const original = within(compare().getByRole("region", { name: "graphCompareOriginal" }));
    const branch = within(compare().getByRole("region", { name: "graphCompareBranch" }));
    expect(screen.getAllByTestId("mock-canvas")).toHaveLength(2);
    expect(original.getByText(FIXTURE_TURNS[1].content)).toBeInTheDocument();
    expect(original.getByText("3.1 s")).toBeInTheDocument();
    expect(branch.getByText("B-2 arrives tomorrow.")).toBeInTheDocument();
    expect(branch.getByText("300 ms")).toBeInTheDocument();

    expect(compare().getByText('graphCompareToolsOnlyOriginal:{"tools":"orders_lookup, billing_specialist"}')).toBeInTheDocument();
    expect(compare().queryByText(/graphCompareToolsOnlyBranch/)).not.toBeInTheDocument();
    expect(compare().getByText("graphCompareSteps: graphCompareOriginal 4 · graphCompareBranch 0")).toBeInTheDocument();
    // The original tail held one more exchange after the forked one.
    expect(compare().getByText("graphCompareRemovedTurnsOne")).toBeInTheDocument();
    expect(compare().queryByText("graphCompareDetached")).not.toBeInTheDocument();

    // Each column inspects its own graph.
    fireEvent.click(original.getByTestId("node-s2"));
    expect(original.getByText("orders_lookup")).toBeInTheDocument();
    expect(branch.getByText("graphInspectHint")).toBeInTheDocument();
  });

  it("says when both versions used the same tools, or none", () => {
    saveSnapshot(FIXTURE_AGENT.id, {
      sessionId: SESSION,
      forkUserIndex: 0,
      originalMessage: "Hi",
      sentMessage: SENT,
      originalTail: [{ role: "user", content: "Hi" }, cleanReply("Hello.")],
    });
    const { rerender } = render(<RunGraphPanel {...props({ turns: branchTurns })} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().getByText("graphCompareNoTools")).toBeInTheDocument();
    expect(compare().queryByText(/graphCompareRemovedTurns/)).not.toBeInTheDocument();

    const toolReply: LabTurn = {
      ...cleanReply("Found it."),
      steps: [
        { type: "tool_call", id: 1, tool: "orders_lookup", origin: "server" },
        { type: "tool_result", id: 1, tool: "orders_lookup", origin: "server" },
        { type: "run_finished", id: 3, origin: "server" },
      ],
    };
    window.localStorage.clear();
    saveSnapshot(FIXTURE_AGENT.id, {
      sessionId: SESSION,
      forkUserIndex: 0,
      originalMessage: "Hi",
      sentMessage: SENT,
      originalTail: [{ role: "user", content: "Hi" }, toolReply],
    });
    rerender(<RunGraphPanel key="again" {...props({ turns: [{ role: "user", content: SENT }, toolReply] })} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().getByText("graphCompareSameTools")).toBeInTheDocument();
  });

  it("shows only the original once the live conversation no longer holds the fork", () => {
    seed();
    render(<RunGraphPanel {...props()} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().getByText("graphCompareDetached")).toBeInTheDocument();
    expect(compare().getByRole("region", { name: "graphCompareOriginal" })).toBeInTheDocument();
    expect(compare().queryByRole("region", { name: "graphCompareBranch" })).not.toBeInTheDocument();
    expect(screen.getAllByTestId("mock-canvas")).toHaveLength(1);
    expect(compare().queryByText(/graphCompareTools|graphCompareSameTools|graphCompareNoTools/)).not.toBeInTheDocument();
  });

  it("discards a snapshot from the view and from storage", () => {
    const snapshot = seed();
    render(<RunGraphPanel {...props({ turns: branchTurns })} />);
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    fireEvent.click(compare().getByRole("button", { name: "graphCompareDiscard" }));
    expect(compare().getByText("graphCompareNone")).toBeInTheDocument();
    expect(loadSnapshots(FIXTURE_AGENT.id).some((s) => s.id === snapshot.id)).toBe(false);
  });

  it("when storage refuses the snapshot: nothing is rewound until the user has been told and confirms again", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    probeSessionEntryCount.mockResolvedValue(4);
    rewindChatSession.mockResolvedValue({ sessionId: SESSION, keepTurns: 2, droppedTurns: 2 });
    const onForkRerun = vi.fn();
    const view = render(<RunGraphPanel {...props({ onForkRerun })} />);
    const dialog = openFork();
    fireEvent.change(dialog.getByLabelText("graphForkMessageLabel"), { target: { value: SENT } });
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    // First press: told, and nothing destructive has happened.
    expect(await dialog.findByRole("alert")).toHaveTextContent("graphForkSnapshotNotStored");
    expect(rewindChatSession).not.toHaveBeenCalled();
    expect(onForkRerun).not.toHaveBeenCalled();
    expect(loadSnapshots(FIXTURE_AGENT.id)).toEqual([]);

    // Second press: goes ahead with the snapshot held in memory.
    fireEvent.click(dialog.getByRole("button", { name: "graphForkConfirm" }));
    await waitFor(() => expect(onForkRerun).toHaveBeenCalledWith(2, SENT));
    expect(rewindChatSession).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(STORE_KEY)).toBeNull();

    // Switching to another lab tab unmounts this panel. The snapshot must
    // still be there when it comes back, storage or no storage.
    view.unmount();
    render(<RunGraphPanel {...props({ onForkRerun, turns: [...FIXTURE_TURNS.slice(0, 2), ...branchTurns] })} />);
    vi.restoreAllMocks();
    fireEvent.click(screen.getByRole("button", { name: "graphCompareOpen" }));
    expect(compare().queryByText("graphCompareNone")).not.toBeInTheDocument();
    expect(compare().getByRole("region", { name: "graphCompareOriginal" })).toBeInTheDocument();
    expect(compare().getByRole("region", { name: "graphCompareBranch" })).toBeInTheDocument();
    expect(compare().getByText("graphCompareNoTools")).toBeInTheDocument();
  });
});
