import { describe, expect, it } from "vitest";
import {
  formatCountdown,
  isTakeoverOpen,
  keyToInput,
  pasteToInput,
  pointerToFramePoint,
  remainingSeconds,
  sessionUsedBrowser,
  tokenRefreshDelayMs,
  wheelToInput,
} from "./browserTakeover";
import type { BrowserTakeoverView } from "./api/browserTakeover";

const view = (state: BrowserTakeoverView["state"]): BrowserTakeoverView => ({
  takeoverId: "t1",
  state,
  requestedBy: "agent",
  kind: "captcha",
  reason: "Solve it",
  host: "shop.example",
  idleRemainingSeconds: 300,
  maxRemainingSeconds: 900,
  waitRemainingSeconds: 0,
});

describe("pointerToFramePoint", () => {
  const rect = { left: 100, top: 50, width: 512, height: 320 };

  it("maps a position inside the box to frame pixels", () => {
    expect(pointerToFramePoint(356, 210, rect, 1024, 640)).toEqual({ x: 512, y: 320 });
    expect(pointerToFramePoint(100, 50, rect, 1024, 640)).toEqual({ x: 0, y: 0 });
  });

  it("clamps a position outside the box and refuses a box with no area", () => {
    expect(pointerToFramePoint(9999, -50, rect, 1024, 640)).toEqual({ x: 1024, y: 0 });
    expect(pointerToFramePoint(10, 10, { left: 0, top: 0, width: 0, height: 0 }, 1024, 640)).toBeNull();
    expect(pointerToFramePoint(10, 10, rect, 0, 640)).toBeNull();
    expect(pointerToFramePoint(Number.NaN, 10, rect, 1024, 640)).toBeNull();
  });
});

describe("keyToInput", () => {
  it("relays named keys, space and single printable characters", () => {
    expect(keyToInput({ key: "Enter" })).toEqual({ type: "key", key: "Enter" });
    expect(keyToInput({ key: "Backspace" })).toEqual({ type: "key", key: "Backspace" });
    expect(keyToInput({ key: " " })).toEqual({ type: "key", key: "Space" });
    expect(keyToInput({ key: "a" })).toEqual({ type: "key", key: "a" });
    expect(keyToInput({ key: "A", shiftKey: true } as never)).toEqual({ type: "key", key: "A" });
    expect(keyToInput({ key: "€" })).toEqual({ type: "key", key: "€" });
    expect(keyToInput({ key: "😀" })).toEqual({ type: "key", key: "😀" });
  });

  it("drops modifiers, shortcuts, function keys and anything else", () => {
    for (const key of ["Shift", "Control", "Alt", "Meta", "CapsLock", "F5", "Dead", "Unidentified", "ContextMenu"]) {
      expect(keyToInput({ key }), key).toBeNull();
    }
    expect(keyToInput({ key: "c", ctrlKey: true })).toBeNull();
    expect(keyToInput({ key: "v", metaKey: true })).toBeNull();
  });
});

describe("pasteToInput", () => {
  it("normalises line ends, drops control characters and caps the length", () => {
    expect(pasteToInput("a\r\nb\u0007c")).toEqual({ type: "type", text: "a\nbc" });
    expect(pasteToInput("x".repeat(500), 256)).toEqual({ type: "type", text: "x".repeat(256) });
    expect(pasteToInput("😀".repeat(300), 10)).toEqual({ type: "type", text: "😀".repeat(10) });
  });

  it("returns null for nothing worth sending", () => {
    expect(pasteToInput("")).toBeNull();
    expect(pasteToInput("\u0007\u0008")).toBeNull();
  });
});

describe("wheelToInput", () => {
  it("converts line and page deltas to pixels and clamps", () => {
    expect(wheelToInput({ deltaX: 0, deltaY: 100, deltaMode: 0 }, { x: 5, y: 6 })).toEqual({ type: "scroll", dx: 0, dy: 100, x: 5, y: 6 });
    expect(wheelToInput({ deltaX: 0, deltaY: 3, deltaMode: 1 }, { x: 0, y: 0 })).toMatchObject({ dy: 48 });
    expect(wheelToInput({ deltaX: 0, deltaY: 99999, deltaMode: 0 }, { x: 0, y: 0 }, 2000)).toMatchObject({ dy: 2000 });
    expect(wheelToInput({ deltaX: 0, deltaY: 0, deltaMode: 0 }, { x: 0, y: 0 })).toBeNull();
  });
});

describe("clocks", () => {
  it("formats a countdown as m:ss and never goes negative", () => {
    expect(formatCountdown(272)).toBe("4:32");
    expect(formatCountdown(5)).toBe("0:05");
    expect(formatCountdown(-3)).toBe("0:00");
    expect(formatCountdown(Number.NaN)).toBe("0:00");
  });

  it("shows whichever limit fires first", () => {
    expect(remainingSeconds(300, 900)).toBe(300);
    expect(remainingSeconds(300, 40)).toBe(40);
    expect(remainingSeconds(-5, 10)).toBe(0);
  });

  it("renews the token at half its life but never tighter than 5 seconds", () => {
    expect(tokenRefreshDelayMs(120)).toBe(60_000);
    expect(tokenRefreshDelayMs(4)).toBe(5_000);
    expect(tokenRefreshDelayMs(Number.NaN)).toBe(30_000);
  });
});

describe("takeover state helpers", () => {
  it("treats paused, in-control and resuming as open", () => {
    expect(isTakeoverOpen(view("PAUSED_FOR_USER"))).toBe(true);
    expect(isTakeoverOpen(view("USER_CONTROL"))).toBe(true);
    expect(isTakeoverOpen(view("RESUMING"))).toBe(true);
    expect(isTakeoverOpen(view("AGENT_CONTROL"))).toBe(false);
    expect(isTakeoverOpen(view("EXPIRED"))).toBe(false);
    expect(isTakeoverOpen(null)).toBe(false);
  });

  it("recognises a session that has driven the browser", () => {
    expect(sessionUsedBrowser([{ type: "tool_call", tool: "browser_navigate" }])).toBe(true);
    expect(sessionUsedBrowser([{ type: "tool_call", tool: "web_search" }, { type: "plan" }])).toBe(false);
    expect(sessionUsedBrowser(undefined)).toBe(false);
  });
});
