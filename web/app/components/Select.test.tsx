// Select's portal contract (2026-09-25). The popover renders into
// document.body via createPortal so it escapes ancestor overflow clipping
// (scrollable drawers) and z-index burial (canvas overlays/dock) - the
// "menus transparent / dropdowns going background / cut off" report. These
// tests pin the failure modes a refactor could silently reintroduce:
//   1. the options panel must NOT live inside .select-root,
//   2. an option CLICK must still commit (the mousedown-outside handler
//      would otherwise close the portal before click fires),
//   3. outside mousedown must still close it.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import Select from "./Select";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

const OPTIONS = [
  { value: "gpt-4o", label: "GPT-4o" },
  { value: "gpt-4o-mini", label: "GPT-4o mini" },
];

function openSelect() {
  const utils = render(<Select options={OPTIONS} value="gpt-4o" onChange={() => {}} />);
  fireEvent.click(utils.getByRole("button"));
  return utils;
}

describe("Select portal", () => {
  it("renders the options panel into document.body, not inside .select-root", () => {
    const { container } = openSelect();
    const listbox = screen.getByRole("listbox");

    expect(listbox).toBeTruthy();
    // The clipping/burial fix: the panel must sit OUTSIDE the trigger's
    // root so no ancestor overflow can cut it off.
    expect(container.querySelector(".select-popover")).toBeNull();
    expect(document.body.contains(listbox)).toBe(true);
    // Fixed positioning = immune to ancestor stacking contexts entirely.
    expect((listbox as HTMLElement).style.position).toBe("fixed");
  });

  it("commits an option on click - outside-mousedown must not close the portal first", async () => {
    const onChange = vi.fn();
    render(<Select options={OPTIONS} value="gpt-4o" onChange={onChange} />);
    fireEvent.click(screen.getByRole("button"));

    fireEvent.mouseDown(screen.getByRole("option", { name: /GPT-4o mini/i }));
    fireEvent.click(screen.getByRole("option", { name: /GPT-4o mini/i }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0].target.value).toBe("gpt-4o-mini");
    // AnimatePresence keeps the closing panel mounted for its exit
    // animation - the assertion is "gone once the exit finishes".
    await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
  });

  it("closes on outside mousedown", async () => {
    openSelect();
    expect(screen.getByRole("listbox")).toBeTruthy();

    fireEvent.mouseDown(document.body);

    await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
  });

  it("flips upward when the trigger sits near the viewport bottom", () => {
    // Fake a trigger rendered at the very bottom of the viewport: jsdom
    // returns zero rects, so pin the rect the positioner reads.
    const spy = vi
      .spyOn(HTMLElement.prototype, "getBoundingClientRect")
      .mockReturnValue({
        top: 790, bottom: 800, left: 20, right: 220, width: 200, height: 10,
        x: 20, y: 790, toJSON: () => ({}),
      } as DOMRect);
    try {
      render(<Select options={OPTIONS} value="gpt-4o" onChange={() => {}} />);
      fireEvent.click(screen.getByRole("button"));

      const listbox = screen.getByRole("listbox") as HTMLElement;
      // Flipped open = anchored by `bottom`, and `top` must be explicitly auto: .select-popover's own CSS
      // sets top: calc(100% + .375rem), and with both edges set the box collapses to its padding and sits
      // off screen (the Assistant's agent picker never opened).
      expect(listbox.style.bottom).not.toBe("");
      expect(listbox.style.top).toBe("auto");
    } finally {
      spy.mockRestore();
    }
  });
});
