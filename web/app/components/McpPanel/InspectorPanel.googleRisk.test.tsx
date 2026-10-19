// The Google tool inspector labels each action read, write or irreversible, and explains that
// irreversible ones (calendar move and cancel) wait for approval.

import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

import InspectorPanel from "./InspectorPanel";
import { GOOGLE_TOOLS } from "./constants";

function renderGoogleCard(toolName: string) {
  const data = GOOGLE_TOOLS.find((g) => g.toolName === toolName);
  if (!data) throw new Error(`no GOOGLE_TOOLS entry for ${toolName}`);
  const noop = () => {};
  const props: any = { card: { kind: "google", data }, agents: [], googleConnection: { connectedEmail: "owner@example.com" } };
  return render(
    <InspectorPanel
      {...props}
      onToggleServer={noop}
      onDisconnectGoogle={noop}
      onRetryConnect={noop}
      onAddCatalog={noop}
      onConnectCatalog={noop}
      onConnectOAuth={noop}
      onRequestEnable={noop}
      onConfirmEnable={noop}
      onCancelEnable={noop}
      onRefresh={noop}
      onApplySuggestedFix={noop}
      onStartEdit={noop}
      onCancelEdit={noop}
      onSubmitEdit={noop}
      onDelete={noop}
      onCancelDelete={noop}
      setEditCommand={noop}
      setEditArgsText={noop}
      setEditEnvText={noop}
      setEditUrl={noop}
    />
  );
}

describe("Google tool risk labels", () => {
  it("lists the three booking tools", () => {
    const names = GOOGLE_TOOLS.map((g) => g.toolName);
    expect(names).toEqual(
      expect.arrayContaining([
        "google_calendar_find_free_slots",
        "google_calendar_move_event",
        "google_calendar_cancel_event",
      ])
    );
  });

  it("marks cancel irreversible and says it needs approval", () => {
    renderGoogleCard("google_calendar_cancel_event");
    expect(screen.getByText("calendar.cancel_event")).toBeInTheDocument();
    expect(screen.getByText("modeIrreversible")).toBeInTheDocument();
    expect(screen.getByText("irreversibleApprovalHint")).toBeInTheDocument();
  });

  it("shows free-slot search as a read with no approval note", () => {
    renderGoogleCard("google_calendar_find_free_slots");
    expect(screen.getByText("modeRead")).toBeInTheDocument();
    expect(screen.queryByText("irreversibleApprovalHint")).toBeNull();
  });

  it("shows Sheets append as a write", () => {
    renderGoogleCard("google_sheets");
    expect(screen.getByText("sheets.append_rows")).toBeInTheDocument();
    expect(screen.getAllByText("modeWrite").length).toBe(2);
  });
});
