import { useRef, useState } from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useDialogFocus, type DialogFocusOptions } from "./useDialogFocus";

function Dialog({ onClose, options, withInitial = true }: { onClose?: () => void; options?: DialogFocusOptions; withInitial?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const initialRef = useRef<HTMLButtonElement>(null);
  useDialogFocus(ref, withInitial ? initialRef : null, onClose, options);
  return (
    <div ref={ref} role="dialog" aria-modal="true" tabIndex={-1}>
      <button type="button">First</button>
      <button type="button" ref={initialRef}>
        Middle
      </button>
      <button type="button">Last</button>
    </div>
  );
}

function Harness({ options }: { options?: DialogFocusOptions }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      {open && <Dialog onClose={() => setOpen(false)} options={options} />}
    </>
  );
}

function openDialog() {
  const opener = screen.getByRole("button", { name: "Open" });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

describe("useDialogFocus", () => {
  it("moves focus to the initial element on open", () => {
    render(<Harness />);
    openDialog();
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Middle" }));
  });

  it("falls back to the first focusable element when no initial ref is given", () => {
    render(<Dialog withInitial={false} />);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "First" }));
  });

  it("wraps Tab from the last element to the first", () => {
    render(<Harness />);
    openDialog();
    const last = screen.getByRole("button", { name: "Last" });
    last.focus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "First" }));
  });

  it("wraps Shift+Tab from the first element to the last", () => {
    render(<Harness />);
    openDialog();
    screen.getByRole("button", { name: "First" }).focus();
    fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Last" }));
  });

  it("pulls focus back inside when it has escaped the dialog", () => {
    render(<Harness />);
    const opener = openDialog();
    opener.focus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "First" }));
  });

  it("does not trap when trap is false", () => {
    render(<Harness options={{ trap: false }} />);
    openDialog();
    const last = screen.getByRole("button", { name: "Last" });
    last.focus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(document.activeElement).toBe(last);
  });

  it("closes on Escape and restores focus to the opener", () => {
    render(<Harness />);
    const opener = openDialog();
    expect(screen.getByRole("dialog")).toBeTruthy();
    act(() => {
      fireEvent.keyDown(window, { key: "Escape" });
    });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("restores focus to the previously focused element on unmount", () => {
    const outside = document.createElement("button");
    document.body.appendChild(outside);
    outside.focus();
    const { unmount } = render(<Dialog />);
    expect(document.activeElement).not.toBe(outside);
    unmount();
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it("does not call anything on Escape when no onClose is given", () => {
    const spy = vi.fn();
    render(<Dialog />);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  it("follows the enabled flag for always-mounted dialogs", () => {
    const outside = document.createElement("button");
    document.body.appendChild(outside);
    outside.focus();
    const { rerender } = render(<Dialog options={{ enabled: false }} />);
    expect(document.activeElement).toBe(outside);
    rerender(<Dialog options={{ enabled: true }} />);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Middle" }));
    rerender(<Dialog options={{ enabled: false }} />);
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });
});
