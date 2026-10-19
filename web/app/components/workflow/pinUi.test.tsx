import { describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";
import type { WorkflowPin } from "../../lib/api/workflowPins";
import { PinsContext, type PinsContextValue } from "./PinsContext";
import PinOutputButton from "./PinOutputButton";
import PinBanner from "./PinBanner";
import PinBadge from "./PinBadge";
import PinEditor from "./PinEditor";
import { DraftRunChip } from "./PinRunSummary";
import { toPinMeta } from "./pinModel";

const pin = (over: Partial<WorkflowPin> = {}): WorkflowPin => ({
  nodeId: "n1",
  source: "test",
  sourceRunId: null,
  pinnedBy: "Asha",
  pinnedAt: "2026-09-30T10:00:00Z",
  sizeChars: 12,
  configHash: null,
  data: '{"a":1}',
  ...over,
});

function ctxOf(over: Partial<PinsContextValue> = {}): PinsContextValue {
  return {
    workflowId: "wf1",
    pins: {},
    loaded: true,
    canEdit: true,
    pinOutput: vi.fn().mockResolvedValue(true),
    unpin: vi.fn().mockResolvedValue(true),
    openEditor: vi.fn(),
    labelOf: (id) => (id === "n1" ? "Fetch orders" : id),
    ...over,
  };
}

function wrap(ctx: PinsContextValue | null, children: ReactNode) {
  return render(
    <I18nProvider locale="en" messages={en} enMessages={en}>
      <PinsContext.Provider value={ctx}>{children}</PinsContext.Provider>
    </I18nProvider>,
  );
}

describe("PinOutputButton", () => {
  it("renders nothing without a pin context, without edit access, or without data", () => {
    const { container, rerender } = render(<PinOutputButton nodeId="n1" data='{"a":1}' source="test" />);
    expect(container.textContent).toBe("");
    rerender(
      <I18nProvider locale="en" messages={en} enMessages={en}>
        <PinsContext.Provider value={ctxOf({ canEdit: false })}>
          <PinOutputButton nodeId="n1" data='{"a":1}' source="test" />
        </PinsContext.Provider>
      </I18nProvider>,
    );
    expect(container.textContent).toBe("");
    rerender(
      <I18nProvider locale="en" messages={en} enMessages={en}>
        <PinsContext.Provider value={ctxOf()}>
          <PinOutputButton nodeId="n1" data="  " source="test" />
        </PinsContext.Provider>
      </I18nProvider>,
    );
    expect(container.textContent).toBe("");
  });

  it("pins clean output straight away with its source and run id", async () => {
    const ctx = ctxOf();
    wrap(ctx, <PinOutputButton nodeId="n1" data={'{\n "a": 1\n}'} source="run" sourceRunId="run-7" truncated={false} />);
    fireEvent.click(screen.getByTestId("pin-output-button"));
    await waitFor(() => expect(ctx.pinOutput).toHaveBeenCalledTimes(1));
    expect(ctx.pinOutput).toHaveBeenCalledWith({ nodeId: "n1", data: '{"a":1}', source: "run", sourceRunId: "run-7" });
    expect(screen.queryByTestId("pin-confirm")).toBeNull();
  });

  it("asks first when the payload is redacted, and only pins after 'Pin anyway'", async () => {
    const ctx = ctxOf();
    wrap(ctx, <PinOutputButton nodeId="n1" data={'{"token":"[redacted]"}'} source="run" label="this" />);
    fireEvent.click(screen.getByTestId("pin-output-button"));
    expect(screen.getByTestId("pin-confirm").textContent).toContain("[redacted]");
    expect(ctx.pinOutput).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Pin anyway" }));
    await waitFor(() => expect(ctx.pinOutput).toHaveBeenCalledTimes(1));
  });

  it("asks first when the payload is truncated, and cancel pins nothing", () => {
    const ctx = ctxOf();
    wrap(ctx, <PinOutputButton nodeId="n1" data={'{"a":'} source="run" truncated />);
    fireEvent.click(screen.getByTestId("pin-output-button"));
    expect(screen.getByTestId("pin-confirm").textContent).toContain("cut");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByTestId("pin-confirm")).toBeNull();
    expect(ctx.pinOutput).not.toHaveBeenCalled();
  });

  it("shows 'Pinned' and disables itself when the same output is already pinned", () => {
    wrap(ctxOf({ pins: { n1: pin({ data: '{"a": 1}' }) } }), <PinOutputButton nodeId="n1" data='{"a":1}' source="test" />);
    const b = screen.getByTestId("pin-output-button") as HTMLButtonElement;
    expect(b.disabled).toBe(true);
    expect(b.textContent).toContain("Pinned");
  });
});

describe("PinBanner", () => {
  it("renders nothing without a provider or before pins loaded", () => {
    const { container } = render(<PinBanner nodeId="n1" />);
    expect(container.textContent).toBe("");
    wrap(ctxOf({ loaded: false, pins: { n1: pin() } }), <PinBanner nodeId="n1" />);
    expect(screen.queryByTestId("pin-banner")).toBeNull();
  });

  it("shows View / Edit / Unpin for an editor and wires them", async () => {
    const ctx = ctxOf({ pins: { n1: pin() } });
    wrap(ctx, <PinBanner nodeId="n1" nodeType="http" />);
    expect(screen.getByTestId("pin-banner").textContent).toContain("Output pinned");
    fireEvent.click(screen.getByRole("button", { name: "View" }));
    expect(ctx.openEditor).toHaveBeenLastCalledWith({ nodeId: "n1", viewOnly: true });
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    expect(ctx.openEditor).toHaveBeenLastCalledWith({ nodeId: "n1" });
    fireEvent.click(screen.getByRole("button", { name: /Unpin/ }));
    await waitFor(() => expect(ctx.unpin).toHaveBeenCalledWith("n1"));
  });

  it("is metadata-only for a viewer without edit access (data withheld)", () => {
    wrap(ctxOf({ canEdit: false, pins: { n1: pin({ data: null }) } }), <PinBanner nodeId="n1" />);
    const banner = screen.getByTestId("pin-banner");
    expect(banner.textContent).toContain("only people who can edit");
    expect(screen.queryByRole("button", { name: "View" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Unpin/ })).toBeNull();
  });

  it("hides write controls but keeps View when the viewer can read but not edit", () => {
    wrap(ctxOf({ canEdit: false, pins: { n1: pin() } }), <PinBanner nodeId="n1" />);
    expect(screen.getByRole("button", { name: "View" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Unpin/ })).toBeNull();
  });

  it("offers manual pinning on an unpinned node, but nothing to a non-editor", () => {
    const ctx = ctxOf();
    const { unmount } = wrap(ctx, <PinBanner nodeId="n1" nodeType="http" />);
    fireEvent.click(screen.getByRole("button", { name: /Pin output/ }));
    expect(ctx.openEditor).toHaveBeenCalledWith({ nodeId: "n1" });
    unmount();
    wrap(ctxOf({ canEdit: false }), <PinBanner nodeId="n1" nodeType="http" />);
    expect(screen.queryByTestId("pin-hint")).toBeNull();
  });
});

describe("PinBadge / DraftRunChip", () => {
  it("describes source, time and size in the tooltip", () => {
    wrap(null, <PinBadge pin={toPinMeta(pin({ sizeChars: 4200 }))} />);
    const badge = screen.getByTestId("pin-badge");
    expect(badge.getAttribute("title")).toContain("a step test");
    expect(badge.getAttribute("title")).toContain("4.2k chars");
    expect(badge.getAttribute("title")).toContain("Draft runs use this");
  });

  it("only appears when nodes were pinned, and says so", () => {
    const { container } = wrap(null, <DraftRunChip count={0} />);
    expect(container.textContent).toBe("");
    wrap(null, <DraftRunChip count={3} />);
    const chip = screen.getByTestId("draft-run-chip");
    expect(chip.textContent).toContain("Draft run - pinned data used");
    expect(chip.textContent).toContain("3 nodes pinned");
  });
});

describe("PinEditor", () => {
  const base = { nodeId: "n1", nodeLabel: "Fetch orders", pin: null as WorkflowPin | null, canEdit: true, onClose: vi.fn() };

  it("blocks malformed JSON with a clear message and keeps Save disabled", () => {
    wrap(null, <PinEditor {...base} onSave={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: '{"a":' } });
    expect(screen.getByTestId("pin-problem").textContent).toContain("does not parse");
    expect((screen.getByRole("button", { name: "Save pin" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("counts characters against 256,000 and refuses an oversize pin", () => {
    wrap(null, <PinEditor {...base} onSave={vi.fn()} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "x".repeat(256_001) } });
    expect(screen.getByTestId("pin-size-counter").textContent).toContain("256,001 of 256,000");
    expect(screen.getByTestId("pin-problem").textContent).toContain("Too large");
    expect((screen.getByRole("button", { name: "Save pin" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("pretty-prints on demand and saves compact JSON as a manual pin", async () => {
    const onSave = vi.fn().mockResolvedValue({ ok: true, data: pin() });
    const onClose = vi.fn();
    wrap(null, <PinEditor {...base} onClose={onClose} onSave={onSave} />);
    const area = screen.getByRole("textbox") as HTMLTextAreaElement;
    fireEvent.change(area, { target: { value: '{"a":[1,2]}' } });
    fireEvent.click(screen.getByRole("button", { name: "Pretty-print" }));
    expect(area.value).toBe('{\n  "a": [\n    1,\n    2\n  ]\n}');
    fireEvent.click(screen.getByRole("button", { name: "Save pin" }));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave).toHaveBeenCalledWith({ nodeId: "n1", data: '{"a":[1,2]}', source: "manual", sourceRunId: undefined });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("shows the server's 400 inline and stays open", async () => {
    const onSave = vi.fn().mockResolvedValue({ ok: false, kind: "too-many", message: "30 pins", status: 400 });
    const onClose = vi.fn();
    wrap(null, <PinEditor {...base} onClose={onClose} onSave={onSave} />);
    fireEvent.change(screen.getByRole("textbox"), { target: { value: '{"a":1}' } });
    fireEvent.click(screen.getByRole("button", { name: "Save pin" }));
    await waitFor(() => expect(screen.getByTestId("pin-server-error").textContent).toContain("already has 30 pins"));
    expect(onClose).not.toHaveBeenCalled();
  });

  it("keeps the original source when an existing pin is saved unchanged", async () => {
    const onSave = vi.fn().mockResolvedValue({ ok: true, data: pin() });
    wrap(null, <PinEditor {...base} pin={pin({ source: "run", sourceRunId: "r9" })} onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: "Save pin" }));
    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][0]).toMatchObject({ source: "run", sourceRunId: "r9" });
  });

  it("is read-only with no Save when the caller cannot edit", () => {
    wrap(null, <PinEditor {...base} pin={pin()} canEdit={false} onSave={vi.fn()} />);
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).readOnly).toBe(true);
    expect(screen.queryByRole("button", { name: "Save pin" })).toBeNull();
  });

  it("shows metadata only, no text box, when the pin data is withheld", () => {
    wrap(null, <PinEditor {...base} pin={pin({ data: null })} canEdit onSave={vi.fn()} />);
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save pin" })).toBeNull();
  });
});
