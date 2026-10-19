// Agent Lab overlay shell: closed means nothing at all is rendered, tabs
// switch between the three (lazy) panels, Escape closes, and a server with
// no lab routes (capabilities null) still shows the panels. The panels and
// the API are mocked here on purpose, so this file stays about the shell no
// matter what the real panels grow into.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

const getLabCapabilities = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
// Each mocked panel carries real controls, the way the real panels do: the
// focus trap is only meaningful when the panels that are mounted but hidden
// contain things a selector would pick up.
vi.mock("./RunGraphPanel", () => ({
  default: () => (
    <div data-testid="mock-graph">
      <button type="button">graph first</button>
      <button type="button" disabled>
        graph disabled
      </button>
      <button type="button">graph last</button>
    </div>
  ),
}));
vi.mock("./KnockoutPanel", () => ({
  default: ({ capabilities }: { capabilities: unknown }) => (
    <div data-testid="mock-knockout" data-capabilities={capabilities === null ? "null" : "set"}>
      <input type="checkbox" aria-label="knockout first" />
      <input type="radio" name="mock-source" aria-label="source a" defaultChecked />
      <input type="radio" name="mock-source" aria-label="source b" />
      <textarea aria-label="knockout probes" />
      <button type="button">knockout run</button>
      <input type="number" aria-label="knockout disabled" disabled />
    </div>
  ),
}));
vi.mock("./ConsistencyPanel", () => ({
  default: () => (
    <div data-testid="mock-consistency">
      <select aria-label="consistency turn">
        <option>1</option>
      </select>
      <textarea aria-label="consistency question" />
      <button type="button">consistency run</button>
    </div>
  ),
}));
vi.mock("./labApi", () => ({ labApi: { getLabCapabilities: (...a: unknown[]) => getLabCapabilities(...a) } }));

import AgentLab from "./AgentLab";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { LabCapabilities } from "./labApi";
import type { AgentLabProps } from "./types";

const CAPABILITIES: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};

function props(overrides: Partial<AgentLabProps> = {}): AgentLabProps {
  return {
    open: true,
    onClose: vi.fn(),
    agent: FIXTURE_AGENT,
    sessionId: "session-1",
    turns: FIXTURE_TURNS,
    busy: false,
    onForkRerun: vi.fn(),
    onCancelTurn: vi.fn(),
    ...overrides,
  };
}

beforeEach(() => {
  getLabCapabilities.mockReset();
  getLabCapabilities.mockResolvedValue(CAPABILITIES);
});

describe("AgentLab", () => {
  it("renders nothing, and asks the server nothing, while closed", () => {
    const { container } = render(<AgentLab {...props({ open: false })} />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(getLabCapabilities).not.toHaveBeenCalled();
  });

  it("opens as a labelled modal dialog on the run graph tab", async () => {
    render(<AgentLab {...props()} />);
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleName("title");
    expect(screen.getByText(`subtitle:${JSON.stringify({ name: FIXTURE_AGENT.name })}`)).toBeInTheDocument();
    expect(await screen.findByTestId("mock-graph")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "tabGraph" })).toHaveAttribute("aria-selected", "true");
    expect(getLabCapabilities).toHaveBeenCalledWith(FIXTURE_AGENT.id);
  });

  it("moves focus to the close button on open", () => {
    render(<AgentLab {...props()} />);
    expect(screen.getByRole("button", { name: "close" })).toHaveFocus();
  });

  it("switches tabs: the graph unmounts, the other two stay mounted and are hidden in turn", async () => {
    render(<AgentLab {...props()} />);
    await screen.findByTestId("mock-graph");
    const knockout = await screen.findByTestId("mock-knockout");
    const consistency = await screen.findByTestId("mock-consistency");
    expect(knockout).not.toBeVisible();
    expect(consistency).not.toBeVisible();

    fireEvent.click(screen.getByRole("tab", { name: "tabKnockout" }));
    expect(screen.queryByTestId("mock-graph")).not.toBeInTheDocument();
    expect(await screen.findByTestId("mock-knockout")).toBeVisible();
    expect(screen.getByTestId("mock-consistency")).not.toBeVisible();

    fireEvent.click(screen.getByRole("tab", { name: "tabConsistency" }));
    expect(await screen.findByTestId("mock-consistency")).toBeVisible();
    // Still mounted (its form state survives), just hidden.
    expect(screen.getByTestId("mock-knockout")).not.toBeVisible();

    fireEvent.click(screen.getByRole("tab", { name: "tabGraph" }));
    expect(await screen.findByTestId("mock-graph")).toBeInTheDocument();
  });

  it("honours initialTab", async () => {
    render(<AgentLab {...props({ initialTab: "consistency" })} />);
    expect(await screen.findByTestId("mock-consistency")).toBeVisible();
    expect(screen.queryByTestId("mock-graph")).not.toBeInTheDocument();
  });

  it("shows the capabilities-loading line instead of the two panels until the answer lands", async () => {
    let resolve: (value: unknown) => void = () => {};
    getLabCapabilities.mockReturnValue(new Promise((r) => (resolve = r)));
    render(<AgentLab {...props({ initialTab: "knockout" })} />);
    expect(screen.getByText("capabilitiesLoading")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-knockout")).not.toBeInTheDocument();
    resolve(CAPABILITIES);
    const knockout = await screen.findByTestId("mock-knockout");
    expect(knockout).toHaveAttribute("data-capabilities", "set");
    expect(screen.queryByText("capabilitiesLoading")).not.toBeInTheDocument();
  });

  it("still renders the panels when capabilities come back null", async () => {
    getLabCapabilities.mockResolvedValue(null);
    render(<AgentLab {...props({ initialTab: "knockout" })} />);
    const knockout = await screen.findByTestId("mock-knockout");
    expect(knockout).toBeVisible();
    expect(knockout).toHaveAttribute("data-capabilities", "null");
    expect(await screen.findByTestId("mock-consistency")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "tabGraph" }));
    expect(await screen.findByTestId("mock-graph")).toBeInTheDocument();
  });

  it("states the scope of a lab run above the knockout and consistency panels", async () => {
    render(<AgentLab {...props({ initialTab: "knockout" })} />);
    await screen.findByTestId("mock-knockout");
    expect(screen.getAllByText("scopeNote")).toHaveLength(2);
  });

  it("Escape calls onClose", async () => {
    const onClose = vi.fn();
    render(<AgentLab {...props({ onClose })} />);
    await screen.findByTestId("mock-graph");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("leaves Escape alone when something inside already handled it", async () => {
    const onClose = vi.fn();
    render(<AgentLab {...props({ onClose })} />);
    await screen.findByTestId("mock-graph");
    const event = new KeyboardEvent("keydown", { key: "Escape", cancelable: true });
    event.preventDefault();
    window.dispatchEvent(event);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("the close button calls onClose", () => {
    const onClose = vi.fn();
    render(<AgentLab {...props({ onClose })} />);
    fireEvent.click(screen.getByRole("button", { name: "close" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  /** Dispatches Tab where the browser would (at the focused element) and says whether the trap took it. */
  function pressTab(shiftKey = false): boolean {
    const event = new KeyboardEvent("keydown", { key: "Tab", shiftKey, bubbles: true, cancelable: true });
    (document.activeElement ?? document.body).dispatchEvent(event);
    return event.defaultPrevented;
  }

  it("graph tab: Tab wraps between the first control and the last VISIBLE one, not one inside a hidden panel", async () => {
    render(<AgentLab {...props()} />);
    await screen.findByTestId("mock-graph");
    // The two other panels are mounted (hidden) after the graph in the DOM,
    // full of inputs. None of them may count as the last control.
    await screen.findByTestId("mock-knockout");
    await screen.findByTestId("mock-consistency");
    const firstTab = screen.getByRole("tab", { name: "tabGraph" });
    const lastVisible = screen.getByRole("button", { name: "graph last" });

    lastVisible.focus();
    expect(pressTab()).toBe(true);
    expect(firstTab).toHaveFocus();
    expect(pressTab(true)).toBe(true);
    expect(lastVisible).toHaveFocus();

    // In between, Tab is left to the browser.
    screen.getByRole("button", { name: "close" }).focus();
    expect(pressTab()).toBe(false);
    expect(screen.getByRole("button", { name: "close" })).toHaveFocus();
  });

  it("knockout tab: the hidden consistency panel after it is not counted, nor a disabled input", async () => {
    render(<AgentLab {...props({ initialTab: "knockout" })} />);
    await screen.findByTestId("mock-knockout");
    await screen.findByTestId("mock-consistency");
    // All three tabs are tab stops (ui/tabs has no roving tabindex).
    const firstTab = screen.getByRole("tab", { name: "tabGraph" });
    // The last enabled control of the visible panel (a disabled input follows it).
    const lastVisible = screen.getByRole("button", { name: "knockout run" });

    lastVisible.focus();
    expect(pressTab()).toBe(true);
    expect(firstTab).toHaveFocus();
    expect(pressTab(true)).toBe(true);
    expect(lastVisible).toHaveFocus();
  });

  it("consistency tab: wraps on its own last control", async () => {
    render(<AgentLab {...props({ initialTab: "consistency" })} />);
    await screen.findByTestId("mock-consistency");
    await screen.findByTestId("mock-knockout");
    const lastVisible = screen.getByRole("button", { name: "consistency run" });
    lastVisible.focus();
    expect(pressTab()).toBe(true);
    expect(screen.getByRole("tab", { name: "tabGraph" })).toHaveFocus();
    expect(pressTab(true)).toBe(true);
    expect(lastVisible).toHaveFocus();
  });

  it("brings focus back in when it is outside the dialog", async () => {
    const outside = document.createElement("button");
    document.body.appendChild(outside);
    try {
      render(<AgentLab {...props()} />);
      await screen.findByTestId("mock-graph");
      outside.focus();
      expect(pressTab()).toBe(true);
      expect(screen.getByRole("tab", { name: "tabGraph" })).toHaveFocus();
      outside.focus();
      expect(pressTab(true)).toBe(true);
      expect(screen.getByRole("button", { name: "graph last" })).toHaveFocus();
    } finally {
      outside.remove();
    }
  });

  it("leaves Tab alone when a dialog inside a panel already handled it", async () => {
    render(<AgentLab {...props()} />);
    await screen.findByTestId("mock-graph");
    const last = screen.getByRole("button", { name: "graph last" });
    last.focus();
    const event = new KeyboardEvent("keydown", { key: "Tab", bubbles: true, cancelable: true });
    event.preventDefault();
    last.dispatchEvent(event);
    expect(last).toHaveFocus();
  });

  it("says so, and mounts no panel, when there is no agent", async () => {
    render(<AgentLab {...props({ agent: null })} />);
    expect(screen.getByText("noAgent")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-graph")).not.toBeInTheDocument();
    expect(screen.queryByTestId("mock-knockout")).not.toBeInTheDocument();
    expect(getLabCapabilities).not.toHaveBeenCalled();
  });
});
