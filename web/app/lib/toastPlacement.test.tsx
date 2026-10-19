// Prod signed-in pass 2026-10-05: an error toast sat on the workflow canvas
// toolbar and swallowed the tester's "Activate" click. The stack must sit
// below any toolbar it would overlap, and only the toast cards themselves
// may take pointer events.

import fs from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render } from "@testing-library/react";
import { TOAST_EDGE_PX, TOAST_TOOLBAR_GAP_PX, toastTopOffset } from "./toastPlacement";
import { toast } from "./toastBus";
import ToastHost from "../components/ToastHost";

vi.mock("./i18n", () => ({ useTranslations: () => (key: string) => key }));

const rect = (top: number, bottom: number, left: number, right: number) => ({ top, bottom, left, right });

describe("toastTopOffset", () => {
  const desktop = { width: 970, height: 786 };
  const phone = { width: 375, height: 812 };
  const header = (vp: { width: number }) => rect(0, 64, 0, vp.width);

  it("rests at the corner when no toolbar is in the way", () => {
    expect(toastTopOffset([], desktop, 352)).toBe(TOAST_EDGE_PX);
  });

  it("drops below the app header", () => {
    expect(toastTopOffset([header(desktop)], desktop, 352)).toBe(64 + TOAST_TOOLBAR_GAP_PX);
  });

  it("drops below the workflow canvas action row (the Activate click that was eaten)", () => {
    const canvasToolbar = rect(72, 122, 260, 960);
    const top = toastTopOffset([header(desktop), canvasToolbar], desktop, 352);
    expect(top).toBe(122 + TOAST_TOOLBAR_GAP_PX);
    expect(top).toBeGreaterThanOrEqual(canvasToolbar.bottom);
  });

  it("clears a toolbar that wrapped to several rows on a 375px phone", () => {
    const wrapped = rect(64, 206, 8, 367);
    expect(toastTopOffset([header(phone), wrapped], phone, 340)).toBe(206 + TOAST_TOOLBAR_GAP_PX);
  });

  it("ignores a bar that is scrolled away, collapsed, far down the page, or left of the stack", () => {
    const scrolledAway = rect(-120, -70, 0, 970);
    const collapsed = rect(64, 64, 0, 970);
    const footerBar = rect(700, 760, 0, 970);
    const leftOnly = rect(72, 122, 0, 500); // stack column starts at 970-18-352 = 600
    expect(toastTopOffset([scrolledAway, collapsed, footerBar, leftOnly], desktop, 352)).toBe(TOAST_EDGE_PX);
  });

  it("never pushes the stack past half the viewport", () => {
    expect(toastTopOffset([rect(0, 300, 0, 375)], { width: 375, height: 500 }, 340)).toBe(250);
  });
});

describe("ToastHost placement", () => {
  afterEach(cleanup);

  it("positions the stack below a [data-toast-avoid] toolbar once a toast shows", () => {
    const bar = document.createElement("div");
    bar.setAttribute("data-toast-avoid", "");
    bar.getBoundingClientRect = () => ({
      ...rect(72, 122, 0, window.innerWidth),
      width: 0,
      height: 50,
      x: 0,
      y: 72,
      toJSON: () => ({}),
    });
    document.body.appendChild(bar);
    try {
      const { container } = render(<ToastHost />);
      const stack = container.querySelector(".hud-toast-stack") as HTMLElement;
      expect(stack.style.top).toBe(""); // nothing showing: no measuring, CSS default
      act(() => {
        toast.success("Workflow activated.", { allowDuplicate: true });
      });
      expect(stack.style.top).toBe(`${122 + TOAST_TOOLBAR_GAP_PX}px`);
    } finally {
      bar.remove();
    }
  });

  it("the stack container takes no pointer events; only a toast card does", () => {
    const css = fs.readFileSync(path.resolve(__dirname, "../styles/globals/hud-toasts-tour.css"), "utf8");
    const block = (selector: string) => {
      const start = css.indexOf(`\n${selector} {`);
      expect(start).toBeGreaterThan(-1);
      return css.slice(start, css.indexOf("}", start));
    };
    expect(block(".hud-toast-stack")).toMatch(/pointer-events:\s*none/);
    expect(block(".hud-toast")).toMatch(/pointer-events:\s*auto/);
  });
});
