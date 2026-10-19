import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import ThreadList from "./ThreadList";

const rows = ["Plan the launch", "Fix the invoice bug", "Weekly report", "Travel ideas", "Read the contract"].map((title, i) => ({
  id: `s${i}`,
  title,
  agentId: "a1",
  lastAt: Date.now() - i,
  pinned: false,
}));

function mount() {
  const fns = { onSelect: vi.fn(), onNew: vi.fn(), onTogglePin: vi.fn(), onRename: vi.fn(), onRemove: vi.fn() };
  render(<ThreadList threads={rows} loaded activeId={null} busy={false} {...fns} />);
  return fns;
}

describe("ThreadList options", () => {
  it("filters conversations by what is typed", () => {
    mount();
    fireEvent.change(screen.getByLabelText(en.assistant.searchThreads), { target: { value: "contract" } });
    expect(screen.queryByText("Plan the launch")).toBeNull();
    expect(screen.getByText("Read the contract")).toBeTruthy();
    fireEvent.change(screen.getByLabelText(en.assistant.searchThreads), { target: { value: "zzz" } });
    expect(screen.getByText(en.assistant.noThreadMatches)).toBeTruthy();
  });

  it("renames on Enter and ignores an empty title", () => {
    const fns = mount();
    fireEvent.click(screen.getAllByLabelText(en.assistant.renameThread)[0]);
    const box = screen.getByRole("textbox", { name: en.assistant.renameThread });
    fireEvent.change(box, { target: { value: "  Launch plan v2 " } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(fns.onRename).toHaveBeenCalledWith("s0", "Launch plan v2");
  });

  it("asks before deleting", () => {
    const fns = mount();
    fireEvent.click(screen.getAllByLabelText(en.assistant.removeThread)[0]);
    expect(fns.onRemove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: en.assistant.delete }));
    expect(fns.onRemove).toHaveBeenCalledWith("s0");
  });

  it("lets the person back out of a delete", () => {
    const fns = mount();
    fireEvent.click(screen.getAllByLabelText(en.assistant.removeThread)[0]);
    fireEvent.click(screen.getByRole("button", { name: en.assistant.cancel }));
    expect(fns.onRemove).not.toHaveBeenCalled();
    expect(screen.getByText("Plan the launch")).toBeTruthy();
  });
});
