import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { goToGuideAnchor } from "./guideDom";

describe("goToGuideAnchor", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    document.body.innerHTML = "";
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("returns false (so the caller can say so) when the control is not on the page", () => {
    expect(goToGuideAnchor("nope")).toBe(false);
  });

  it("opens a collapsed accordion by clicking its closed trigger, then scrolls and flashes", () => {
    document.body.innerHTML = `
      <div id="sec"><h3><button data-guide-trigger="" data-state="closed" aria-controls="c1">Knowledge</button></h3></div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    const clicked = vi.fn();
    sec.querySelector("button")!.addEventListener("click", clicked);
    sec.scrollIntoView = vi.fn();

    expect(goToGuideAnchor("sec")).toBe(true);
    expect(clicked).toHaveBeenCalledTimes(1);
    expect(sec.scrollIntoView).toHaveBeenCalled();
    expect(sec.style.outline).toContain("var(--primary)");

    vi.runAllTimers();
    expect(sec.style.outline).toBe("");
    expect(sec.dataset.guideOutline).toBeUndefined();
  });

  it("does not click an already-open section", () => {
    document.body.innerHTML = `<div id="sec"><button data-guide-trigger="" data-state="open" aria-controls="c1">Tools</button></div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    const clicked = vi.fn();
    sec.querySelector("button")!.addEventListener("click", clicked);
    sec.scrollIntoView = vi.fn();
    goToGuideAnchor("sec");
    expect(clicked).not.toHaveBeenCalled();
  });

  it("an open section with a nested closed Radix trigger (popover, dialog) leaves that trigger alone", () => {
    document.body.innerHTML = `
      <div id="sec">
        <button data-guide-trigger="" data-state="open" aria-controls="c1">Tools</button>
        <div id="c1"><button id="nested" data-state="closed" aria-controls="pop1">More options</button></div>
      </div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    const nestedClicked = vi.fn();
    document.getElementById("nested")!.addEventListener("click", nestedClicked);
    sec.scrollIntoView = vi.fn();
    expect(goToGuideAnchor("sec")).toBe(true);
    expect(nestedClicked).not.toHaveBeenCalled();
  });

  it("focuses the field marked data-guide-focus, not an earlier helper textarea", () => {
    document.body.innerHTML = `
      <div id="sec">
        <div class="actions"><textarea id="helper"></textarea></div>
        <div class="body"><textarea id="real" data-guide-focus=""></textarea></div>
      </div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    sec.scrollIntoView = vi.fn();
    goToGuideAnchor("sec");
    vi.advanceTimersByTime(100);
    expect(document.activeElement).toBe(document.getElementById("real"));
  });

  it("falls back to the first enabled text field when nothing is marked", () => {
    document.body.innerHTML = `<div id="sec"><input type="checkbox" /><input id="first" /></div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    sec.scrollIntoView = vi.fn();
    goToGuideAnchor("sec");
    vi.advanceTimersByTime(100);
    expect(document.activeElement).toBe(document.getElementById("first"));
  });

  it("focuses the field itself when the anchor is an input", () => {
    document.body.innerHTML = `<input id="name" />`;
    const input = document.getElementById("name") as HTMLInputElement;
    input.scrollIntoView = vi.fn();
    goToGuideAnchor("name");
    vi.advanceTimersByTime(100);
    expect(document.activeElement).toBe(input);
  });

  it("restores the author's own outline, even when flashed twice", () => {
    document.body.innerHTML = `<div id="sec" style="outline: 1px dotted red"></div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    sec.scrollIntoView = vi.fn();
    goToGuideAnchor("sec");
    goToGuideAnchor("sec");
    vi.runAllTimers();
    expect(sec.style.outline).toBe("1px dotted red");
  });

  it("survives a browser whose scrollIntoView rejects the options object", () => {
    document.body.innerHTML = `<div id="sec"></div>`;
    const sec = document.getElementById("sec") as HTMLElement;
    let calls = 0;
    sec.scrollIntoView = vi.fn(() => {
      calls += 1;
      if (calls === 1) throw new Error("options unsupported");
    });
    expect(() => goToGuideAnchor("sec")).not.toThrow();
    expect(calls).toBe(2);
  });
});
