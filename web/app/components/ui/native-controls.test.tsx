import { describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Select } from "./select";
import { Checkbox } from "./checkbox";
import { Combobox } from "./combobox";
import { ConfirmDialog } from "./confirm-dialog";

const OPTS = [
  { value: "a", label: "Alpha" },
  { value: "b", label: "Beta", disabled: true },
  { value: "c", label: "Gamma" },
];

function SelectHarness({ onChange }: { onChange: (v: string) => void }) {
  const [v, setV] = useState("a");
  return <Select aria-label="Pick" options={OPTS} value={v} onValueChange={(x) => { setV(x); onChange(x); }} />;
}

describe("Select", () => {
  it("exposes combobox/listbox roles and selects with the keyboard, skipping disabled", () => {
    const onChange = vi.fn();
    render(<SelectHarness onChange={onChange} />);
    const trigger = screen.getByRole("combobox", { name: "Pick" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("listbox")).toBeInTheDocument();
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(trigger, { key: "ArrowDown" });
    fireEvent.keyDown(trigger, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("c");
    expect(screen.queryByRole("listbox")).toBeNull();
  });
  it("closes on Escape without selecting", () => {
    const onChange = vi.fn();
    render(<SelectHarness onChange={onChange} />);
    const trigger = screen.getByRole("combobox");
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe("Checkbox", () => {
  it("maps state to aria-checked including mixed, and toggles", () => {
    const onChange = vi.fn();
    const { rerender } = render(<Checkbox aria-label="all" checked="indeterminate" onCheckedChange={onChange} />);
    const box = screen.getByRole("checkbox", { name: "all" });
    expect(box).toHaveAttribute("aria-checked", "mixed");
    fireEvent.click(box);
    expect(onChange).toHaveBeenLastCalledWith(true);
    rerender(<Checkbox aria-label="all" checked onCheckedChange={onChange} />);
    expect(box).toHaveAttribute("aria-checked", "true");
    fireEvent.click(box);
    expect(onChange).toHaveBeenLastCalledWith(false);
  });
});

describe("Combobox", () => {
  it("filters locally and selects with Enter", () => {
    const onChange = vi.fn();
    render(<Combobox aria-label="Find" options={OPTS} onValueChange={onChange} />);
    const input = screen.getByRole("combobox", { name: "Find" });
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "gam" } });
    expect(screen.getAllByRole("option")).toHaveLength(1);
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("c");
  });
  it("delegates to onSearch and shows loading", () => {
    const onSearch = vi.fn();
    render(<Combobox aria-label="Find" options={[]} onSearch={onSearch} loading />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "x" } });
    expect(onSearch).toHaveBeenCalledWith("x");
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });
});

describe("ConfirmDialog", () => {
  it("is an alertdialog and confirms", async () => {
    const onConfirm = vi.fn();
    const onOpenChange = vi.fn();
    render(<ConfirmDialog open onOpenChange={onOpenChange} title="Delete?" description="Gone" confirmLabel="Delete" destructive onConfirm={onConfirm} />);
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(undefined));
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
  });
  it("requires a reason before confirming", async () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog open onOpenChange={() => {}} title="Revoke" requireReason onConfirm={onConfirm} />);
    const btn = screen.getByRole("button", { name: "Confirm" });
    expect(btn).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "  abuse " } });
    expect(btn).toBeEnabled();
    fireEvent.click(btn);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith("abuse"));
  });
});
