// The unified-memory items panel: grouping, the flag-off empty state, the
// approve/reject review actions and the per-user export button.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations:
    (namespace: string) =>
    (key: string, values?: Record<string, unknown>): string => {
      const template =
        (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`;
      return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
    },
  useLocale: () => "en-GB",
}));

const api = vi.hoisted(() => ({
  listMemoryItems: vi.fn(),
  getMemoryItem: vi.fn(),
  approveMemoryItem: vi.fn(),
  rejectMemoryItem: vi.fn(),
  revertMemoryItem: vi.fn(),
  eraseMemoryItem: vi.fn(),
  exportMemoryItems: vi.fn(),
}));
vi.mock("../lib/api", () => ({ api }));
vi.mock("../lib/api/core", async (orig) => {
  const mod = (await orig()) as Record<string, unknown>;
  return { ...mod, triggerBrowserDownload: vi.fn() };
});

import MemoryItemsPanel from "./MemoryItemsPanel";

const AGENTS = [
  { id: "a1", name: "Alpha", accessLevel: "EDITOR" as const },
  { id: "a2", name: "Beta", accessLevel: "VIEWER" as const },
];

const ITEMS = [
  {
    id: "i1",
    agentId: "a1",
    kind: "SEMANTIC",
    content: "likes tea",
    status: "PENDING",
    trust: "TRUSTED",
    createdAt: "2026-10-01T10:00:00Z",
  },
  {
    id: "i2",
    agentId: "a1",
    kind: "EPISODIC",
    content: "met on Tuesday",
    status: "ACTIVE",
    trust: "TRUSTED",
    createdAt: "2026-10-02T10:00:00Z",
  },
  {
    id: "i3",
    agentId: "a1",
    kind: "SEMANTIC",
    content: "old fact",
    status: "REVERTED",
    trust: "TRUSTED",
    createdAt: "2026-09-30T10:00:00Z",
  },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.listMemoryItems.mockResolvedValue(ITEMS);
  api.exportMemoryItems.mockResolvedValue({ blob: new Blob(["{}"]), filename: "memory-items-export.json" });
});

describe("MemoryItemsPanel", () => {
  it("lists the first agent's items grouped by review / active / tombstoned", async () => {
    render(<MemoryItemsPanel agents={AGENTS} />);
    await waitFor(() => expect(api.listMemoryItems).toHaveBeenCalledWith("a1", undefined));
    expect(await screen.findByTestId("memory-items-review")).toBeInTheDocument();
    expect(within(screen.getByTestId("memory-items-review")).getByText("likes tea")).toBeInTheDocument();
    expect(within(screen.getByTestId("memory-items-active")).getByText("met on Tuesday")).toBeInTheDocument();
    expect(within(screen.getByTestId("memory-items-tombstoned")).getByText("old fact")).toBeInTheDocument();
  });

  it("shows approve and reject for a pending item when the caller is an editor", async () => {
    render(<MemoryItemsPanel agents={AGENTS} />);
    const review = await screen.findByTestId("memory-items-review");
    expect(within(review).getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(within(review).getByRole("button", { name: "Reject" })).toBeInTheDocument();
    api.approveMemoryItem.mockResolvedValue(undefined);
    fireEvent.click(within(review).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(api.approveMemoryItem).toHaveBeenCalledWith("a1", "i1"));
    await waitFor(() => expect(api.listMemoryItems).toHaveBeenCalledTimes(2));
  });

  it("rejects a pending item", async () => {
    api.rejectMemoryItem.mockResolvedValue(undefined);
    render(<MemoryItemsPanel agents={AGENTS} />);
    const review = await screen.findByTestId("memory-items-review");
    fireEvent.click(within(review).getByRole("button", { name: "Reject" }));
    await waitFor(() => expect(api.rejectMemoryItem).toHaveBeenCalledWith("a1", "i1"));
    await waitFor(() => expect(api.listMemoryItems).toHaveBeenCalledTimes(2));
  });

  it("erases an item only after the confirmation dialog", async () => {
    api.eraseMemoryItem.mockResolvedValue(undefined);
    render(<MemoryItemsPanel agents={AGENTS} />);
    const active = await screen.findByTestId("memory-items-active");
    fireEvent.click(within(active).getByRole("button", { name: "Erase" }));
    expect(api.eraseMemoryItem).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText("Erase this memory item?")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Erase" }));
    await waitFor(() => expect(api.eraseMemoryItem).toHaveBeenCalledWith("a1", "i2"));
  });

  it("shows the empty state when the agent has no items", async () => {
    api.listMemoryItems.mockResolvedValue([]);
    render(<MemoryItemsPanel agents={AGENTS} />);
    expect(await screen.findByText("No memory items yet")).toBeInTheDocument();
  });

  it("shows an error with retry when the list call fails", async () => {
    api.listMemoryItems.mockRejectedValueOnce(new Error("boom"));
    render(<MemoryItemsPanel agents={AGENTS} />);
    expect(await screen.findByText("Could not load memory items")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Try again" })[0]);
    await waitFor(() => expect(api.listMemoryItems).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId("memory-items-review")).toBeInTheDocument();
  });

  it("badges a quarantined item and keeps it in the review group", async () => {
    api.listMemoryItems.mockResolvedValue([
      { ...ITEMS[1], id: "i4", content: "suspicious write", status: "ACTIVE", trust: "QUARANTINED" },
    ]);
    render(<MemoryItemsPanel agents={AGENTS} />);
    const review = await screen.findByTestId("memory-items-review");
    expect(within(review).getByText("suspicious write")).toBeInTheDocument();
    expect(within(review).getByText("Quarantined")).toBeInTheDocument();
    expect(screen.queryByTestId("memory-items-active")).not.toBeInTheDocument();
  });

  it("shows revert for an active item", async () => {
    render(<MemoryItemsPanel agents={AGENTS} />);
    const active = await screen.findByTestId("memory-items-active");
    fireEvent.click(within(active).getByRole("button", { name: "Revert" }));
    await waitFor(() => expect(api.revertMemoryItem).toHaveBeenCalledWith("a1", "i2"));
  });

  it("hides review actions for a viewer", async () => {
    api.listMemoryItems.mockResolvedValue([ITEMS[1]]);
    render(<MemoryItemsPanel agents={[AGENTS[1]]} />);
    await waitFor(() => expect(api.listMemoryItems).toHaveBeenCalledWith("a2", undefined));
    const active = await screen.findByTestId("memory-items-active");
    expect(within(active).queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(within(active).queryByRole("button", { name: "Revert" })).not.toBeInTheDocument();
    expect(within(active).queryByRole("button", { name: "Erase" })).not.toBeInTheDocument();
  });

  it("shows the feature-off empty state when the list answers 404", async () => {
    api.listMemoryItems.mockResolvedValue(null);
    render(<MemoryItemsPanel agents={AGENTS} />);
    expect(await screen.findByText("Unified memory is off for this assistant")).toBeInTheDocument();
  });

  it("shows lineage and events once the detail loads", async () => {
    api.getMemoryItem.mockResolvedValue({
      item: ITEMS[0],
      lineage: [{ sourceTable: "chat_message", sourceKey: "m-9", sourceTrust: "TRUSTED" }],
      events: [
        {
          id: "e1",
          event: "CREATED",
          actorType: "AGENT",
          actorRef: "a1",
          fromStatus: null,
          toStatus: "PENDING",
          at: "2026-10-01T10:00:00Z",
        },
      ],
    });
    render(<MemoryItemsPanel agents={AGENTS} />);
    const review = await screen.findByTestId("memory-items-review");
    fireEvent.click(within(review).getByRole("button", { name: "Show details" }));
    await waitFor(() => expect(api.getMemoryItem).toHaveBeenCalledWith("a1", "i1"));
    const detail = await screen.findByTestId("memory-item-detail");
    expect(within(detail).getByText("Lineage")).toBeInTheDocument();
    expect(within(detail).getByText(/chat_message/)).toBeInTheDocument();
    expect(within(detail).getByText(/Created/)).toBeInTheDocument();
    expect(within(detail).getByText(/- to Pending/)).toBeInTheDocument();
  });

  it("downloads the per-user export and hides the button when it 404s", async () => {
    const { triggerBrowserDownload } = await import("../lib/api/core");
    render(<MemoryItemsPanel agents={AGENTS} />);
    const btn = await screen.findByRole("button", { name: "Export my items" });
    fireEvent.click(btn);
    await waitFor(() => expect(triggerBrowserDownload).toHaveBeenCalled());
    api.exportMemoryItems.mockResolvedValue(null);
    fireEvent.click(await screen.findByRole("button", { name: "Export my items" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Export my items" })).not.toBeInTheDocument());
  });
});
