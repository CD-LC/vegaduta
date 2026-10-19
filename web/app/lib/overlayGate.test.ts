import { afterEach, describe, expect, it } from "vitest";
import {
  TOUR_OFF_KEY,
  higherEscapeLayerOpen,
  isEditingSurface,
  overlayHold,
  tourSuppressed,
  userIsBusy,
} from "./overlayGate";

afterEach(() => {
  document.body.innerHTML = "";
  try {
    window.localStorage.clear();
  } catch {
    /* ignore */
  }
});

describe("isEditingSurface", () => {
  it("flags deep links into editors and the chat playground", () => {
    for (const p of ["/workflows/abc", "/workflows/abc/runs", "/build/xyz", "/playground", "/agents", "/agents/quality"]) {
      expect(isEditingSurface(p), p).toBe(true);
    }
  });
  it("leaves list and landing pages alone", () => {
    for (const p of ["/", "/home", "/workflows", "/build", "/knowledge", "/admin", "/agentsx"]) {
      expect(isEditingSurface(p), p).toBe(false);
    }
  });
});

describe("userIsBusy", () => {
  it("is false on an idle page", () => {
    document.body.innerHTML = `<button id="b">x</button>`;
    document.getElementById("b")!.focus();
    expect(userIsBusy()).toBe(false);
  });
  it("is true while a text field or textarea has focus", () => {
    document.body.innerHTML = `<textarea id="t"></textarea><input id="i" type="text" />`;
    document.getElementById("t")!.focus();
    expect(userIsBusy()).toBe(true);
    document.getElementById("i")!.focus();
    expect(userIsBusy()).toBe(true);
  });
  it("ignores focused checkboxes", () => {
    document.body.innerHTML = `<input id="c" type="checkbox" />`;
    document.getElementById("c")!.focus();
    expect(userIsBusy()).toBe(false);
  });
  it("is true while a turn is marked in flight", () => {
    document.body.innerHTML = `<div data-turn-in-flight="true"></div>`;
    expect(userIsBusy()).toBe(true);
  });
  it("does not treat aria-busy skeletons as a running turn", () => {
    document.body.innerHTML = `<div aria-busy="true"></div>`;
    expect(userIsBusy()).toBe(false);
  });
});

describe("overlayHold", () => {
  it("is none on a quiet list page", () => {
    expect(overlayHold("/home")).toBe("none");
  });
  it("is surface on an editing surface, busy when typing elsewhere", () => {
    expect(overlayHold("/workflows/abc")).toBe("surface");
    document.body.innerHTML = `<input id="i" type="search" />`;
    document.getElementById("i")!.focus();
    expect(overlayHold("/home")).toBe("busy");
  });
  it("honours the user-independent vd_tour_off switch", () => {
    window.localStorage.setItem(TOUR_OFF_KEY, "1");
    expect(tourSuppressed()).toBe(true);
    expect(overlayHold("/home")).toBe("surface");
  });
});

describe("higherEscapeLayerOpen", () => {
  it("sees an open command palette", () => {
    expect(higherEscapeLayerOpen()).toBe(false);
    document.body.innerHTML = `<div data-escape-layer="command-palette"></div>`;
    expect(higherEscapeLayerOpen()).toBe(true);
  });
});
