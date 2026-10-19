import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { McpToolPinStatus } from "../../lib/api/mcpToolPins";

const list = vi.fn();
const approve = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    listChangedMcpToolPins: (...a: unknown[]) => list(...a),
    approveMcpToolPins: (...a: unknown[]) => approve(...a),
  },
}));

import ToolPinReview, { pinModeNote } from "./ToolPinReview";

const item = (over: Partial<McpToolPinStatus> = {}): McpToolPinStatus => ({
  registrationId: "r1",
  name: "weather",
  mode: "enforce",
  pinned: true,
  changed: true,
  pinnedHash: "a",
  observedHash: "b",
  pinnedAt: null,
  pinnedBy: "admin",
  changedAt: null,
  added: ["new_tool"],
  removed: [],
  changedTools: ["get_weather"],
  ...over,
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ToolPinReview", () => {
  it("renders nothing when no server changed", async () => {
    list.mockResolvedValue([]);
    const { container } = render(<ToolPinReview />);
    await waitFor(() => expect(list).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing when the endpoint fails", async () => {
    list.mockRejectedValue(new Error("404"));
    const { container } = render(<ToolPinReview />);
    await waitFor(() => expect(list).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("shows what changed and approves, then reloads and notifies", async () => {
    list.mockResolvedValueOnce([item()]).mockResolvedValueOnce([]);
    approve.mockResolvedValue(item({ changed: false }));
    const onChanged = vi.fn();
    render(<ToolPinReview onChanged={onChanged} />);
    expect(await screen.findByText("get_weather")).toBeTruthy();
    expect(screen.getByText("new_tool")).toBeTruthy();
    fireEvent.click(screen.getByTestId("mcp-tool-pin-approve-weather"));
    await waitFor(() => expect(approve).toHaveBeenCalledWith("r1"));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByTestId("mcp-tool-pin-review")).toBeNull());
  });

  it("shows the error and keeps the card when approval fails", async () => {
    list.mockResolvedValue([item()]);
    approve.mockRejectedValue(new Error("forbidden"));
    render(<ToolPinReview />);
    fireEvent.click(await screen.findByTestId("mcp-tool-pin-approve-weather"));
    expect(await screen.findByText("forbidden")).toBeTruthy();
    expect(screen.getByTestId("mcp-tool-pin-review")).toBeTruthy();
  });

  it("explains the mode honestly", () => {
    expect(pinModeNote("enforce")).toMatch(/cannot use/);
    expect(pinModeNote("warn")).toMatch(/still using/);
  });
});
