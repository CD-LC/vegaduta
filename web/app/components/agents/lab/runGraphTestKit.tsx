// Shared fixtures for the RunGraphPanel test files (selector/inspector/canvas, fork, compare). Each
// test file keeps its own vi.hoisted HTTP mocks and vi.mock calls (hoisted per file); this module
// holds the stand-in canvas, the props builder and the screen helpers.

import { vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { LabTurn, RunGraphPanelProps } from "./types";

// Stand-in canvas: one plain button per node, so a "click on a node" is a
// click here. (The real node surface is a div inside React Flow.) Test files
// install it with vi.mock("./RunGraphCanvas", ...).
export function MockRunGraphCanvas({
  graph,
  selectedId,
  onSelect,
  ariaLabel,
}: {
  graph: { nodes: { id: string; kind: string; status: string }[] };
  selectedId: string | null;
  onSelect: (id: string) => void;
  ariaLabel: string;
}) {
  return (
    <div data-testid="mock-canvas" aria-label={ariaLabel} data-selected={selectedId ?? ""}>
      {graph.nodes.map((n) => (
        <button key={n.id} type="button" data-testid={`node-${n.id}`} data-status={n.status} onClick={() => onSelect(n.id)}>
          {n.kind}
        </button>
      ))}
    </div>
  );
}

export const SESSION = "session-1";
export const STORE_KEY = `agentic:lab:forks:${FIXTURE_AGENT.id}`;

export function props(overrides: Partial<RunGraphPanelProps> = {}): RunGraphPanelProps {
  return {
    agent: FIXTURE_AGENT,
    sessionId: SESSION,
    turns: FIXTURE_TURNS,
    busy: false,
    onForkRerun: vi.fn(),
    onCancelTurn: vi.fn(),
    ...overrides,
  };
}

export const cleanReply = (content: string): LabTurn => ({
  role: "assistant",
  content,
  ref: `ref-${content}`,
  steps: [
    { type: "run_started", id: 1, origin: "server" },
    { type: "run_finished", id: 2, ms: 300, origin: "server" },
  ],
  metrics: { elapsedMs: 300, toolCalls: 0 },
});

export const turnSelect = () => screen.getByLabelText("graphTurnLabel");
export const followBox = () => screen.getByLabelText("graphFollowLatest") as HTMLInputElement;
export const inspector = () => within(screen.getByTestId("lab-graph-inspector"));
export const nodeIds = () =>
  within(screen.getByTestId("mock-canvas"))
    .getAllByRole("button")
    .map((b) => b.getAttribute("data-testid"));

/** Opens the fork dialog for the exchange on screen (select its user node, press Fork). */
export function openFork() {
  fireEvent.click(screen.getByTestId("node-u"));
  fireEvent.click(inspector().getByRole("button", { name: "graphFork" }));
  return within(screen.getByRole("dialog"));
}
