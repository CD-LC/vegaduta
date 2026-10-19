// Per-message branching controls: what shows for which role, the clamped version switcher, and
// the inline editor's keyboard contract (Ctrl/Cmd+Enter saves, Escape cancels, blank cannot save).

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

import { BranchSwitcher, ChatBranchTurnBar, EditMessageForm } from "./ChatBranchControls";

describe("BranchSwitcher", () => {
  it("shows the 1-based position and steps one version at a time", () => {
    const onPrevious = vi.fn();
    const onNext = vi.fn();
    render(<BranchSwitcher index={1} count={3} onPrevious={onPrevious} onNext={onNext} />);
    expect(screen.getByText("2/3")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("previousVersion"));
    fireEvent.click(screen.getByLabelText("nextVersion"));
    expect(onPrevious).toHaveBeenCalledTimes(1);
    expect(onNext).toHaveBeenCalledTimes(1);
  });

  it("disables the buttons at both ends instead of wrapping", () => {
    const { rerender } = render(<BranchSwitcher index={0} count={2} onPrevious={vi.fn()} onNext={vi.fn()} />);
    expect(screen.getByLabelText("previousVersion")).toBeDisabled();
    expect(screen.getByLabelText("nextVersion")).not.toBeDisabled();
    rerender(<BranchSwitcher index={1} count={2} onPrevious={vi.fn()} onNext={vi.fn()} />);
    expect(screen.getByLabelText("nextVersion")).toBeDisabled();
  });

  it("disables both buttons while a switch is in flight", () => {
    render(<BranchSwitcher index={1} count={3} disabled onPrevious={vi.fn()} onNext={vi.fn()} />);
    expect(screen.getByLabelText("previousVersion")).toBeDisabled();
    expect(screen.getByLabelText("nextVersion")).toBeDisabled();
  });
});

// The bar's `role` prop is the chat message role, not an ARIA role; passing it through constants keeps
// jsx-a11y/aria-role (which reads string literals on any JSX `role`) from mistaking it for one.
const USER = "user" as const;
const ASSISTANT = "assistant" as const;

describe("ChatBranchTurnBar", () => {
  const handlers = () => ({ onEdit: vi.fn(), onRegenerate: vi.fn(), onSwitch: vi.fn() });

  it("offers Edit on a user message and not Regenerate", () => {
    const h = handlers();
    render(<ChatBranchTurnBar role={USER} siblingIndex={0} siblingCount={1} busy={false} {...h} />);
    fireEvent.click(screen.getByLabelText("edit"));
    expect(h.onEdit).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText("regenerate")).not.toBeInTheDocument();
  });

  it("offers Regenerate on an assistant message and not Edit", () => {
    const h = handlers();
    render(<ChatBranchTurnBar role={ASSISTANT} siblingIndex={0} siblingCount={1} busy={false} {...h} />);
    fireEvent.click(screen.getByLabelText("regenerate"));
    expect(h.onRegenerate).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText("edit")).not.toBeInTheDocument();
  });

  it("shows the switcher only when there is more than one version, and reports the direction", () => {
    const h = handlers();
    const { rerender } = render(
      <ChatBranchTurnBar role={ASSISTANT} siblingIndex={0} siblingCount={1} busy={false} {...h} />
    );
    expect(screen.queryByTestId("branch-switcher")).not.toBeInTheDocument();
    rerender(<ChatBranchTurnBar role={ASSISTANT} siblingIndex={1} siblingCount={3} busy={false} {...h} />);
    expect(screen.getByTestId("branch-switcher")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("previousVersion"));
    fireEvent.click(screen.getByLabelText("nextVersion"));
    expect(h.onSwitch).toHaveBeenNthCalledWith(1, -1);
    expect(h.onSwitch).toHaveBeenNthCalledWith(2, 1);
  });

  it("disables the actions while busy", () => {
    const h = handlers();
    render(<ChatBranchTurnBar role={USER} siblingIndex={0} siblingCount={2} busy {...h} />);
    expect(screen.getByLabelText("edit")).toBeDisabled();
    expect(screen.getByLabelText("previousVersion")).toBeDisabled();
  });

  it("reveals the actions on keyboard focus, not only on hover", () => {
    render(<ChatBranchTurnBar role={USER} siblingIndex={0} siblingCount={1} busy={false} {...handlers()} />);
    const wrapper = screen.getByLabelText("edit").parentElement as HTMLElement;
    expect(wrapper.className).toContain("focus-within:opacity-100");
    expect(wrapper.className).toContain("group-focus-within:opacity-100");
  });
});

describe("EditMessageForm", () => {
  it("opens focused on the textarea with the current text", () => {
    render(<EditMessageForm initialText="hello there" busy={false} onSave={vi.fn()} onCancel={vi.fn()} />);
    const box = screen.getByLabelText("editLabel") as HTMLTextAreaElement;
    expect(box.value).toBe("hello there");
    expect(document.activeElement).toBe(box);
  });

  it("saves the edited text with the button", () => {
    const onSave = vi.fn();
    render(<EditMessageForm initialText="old" busy={false} onSave={onSave} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("editLabel"), { target: { value: "new text" } });
    fireEvent.click(screen.getByText("saveAndSubmit"));
    expect(onSave).toHaveBeenCalledWith("new text");
  });

  it("saves with Ctrl+Enter and with Cmd+Enter, but not with a plain Enter", () => {
    const onSave = vi.fn();
    render(<EditMessageForm initialText="text" busy={false} onSave={onSave} onCancel={vi.fn()} />);
    const box = screen.getByLabelText("editLabel");
    fireEvent.keyDown(box, { key: "Enter" });
    expect(onSave).not.toHaveBeenCalled();
    fireEvent.keyDown(box, { key: "Enter", ctrlKey: true });
    fireEvent.keyDown(box, { key: "Enter", metaKey: true });
    expect(onSave).toHaveBeenCalledTimes(2);
  });

  it("cancels with Escape and with the Cancel button", () => {
    const onCancel = vi.fn();
    render(<EditMessageForm initialText="text" busy={false} onSave={vi.fn()} onCancel={onCancel} />);
    fireEvent.keyDown(screen.getByLabelText("editLabel"), { key: "Escape" });
    fireEvent.click(screen.getByText("cancel"));
    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it("cannot submit blank text", () => {
    const onSave = vi.fn();
    render(<EditMessageForm initialText="text" busy={false} onSave={onSave} onCancel={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("editLabel"), { target: { value: "   " } });
    expect(screen.getByText("saveAndSubmit")).toBeDisabled();
    fireEvent.keyDown(screen.getByLabelText("editLabel"), { key: "Enter", ctrlKey: true });
    expect(onSave).not.toHaveBeenCalled();
  });

  it("locks the editor while a turn is running", () => {
    render(<EditMessageForm initialText="text" busy onSave={vi.fn()} onCancel={vi.fn()} />);
    expect(screen.getByLabelText("editLabel")).toBeDisabled();
    expect(screen.getByText("saveAndSubmit")).toBeDisabled();
  });
});
