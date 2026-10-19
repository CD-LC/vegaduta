// tabbableWithin: the list both lab focus traps wrap on. It has to be what
// the Tab key can really reach - anything more and the trap never fires on
// the real last control.

import { afterEach, describe, expect, it } from "vitest";
import { tabbableWithin } from "./focusables";

function mount(html: string): HTMLElement {
  const root = document.createElement("div");
  root.innerHTML = html;
  document.body.appendChild(root);
  return root;
}

const labels = (root: HTMLElement) => tabbableWithin(root).map((el) => el.getAttribute("data-t"));

afterEach(() => {
  document.body.innerHTML = "";
});

describe("tabbableWithin", () => {
  it("lists links, buttons, fields and tabindex 0 elements in DOM order", () => {
    const root = mount(`
      <a href="#x" data-t="link">x</a>
      <a data-t="anchor-without-href">x</a>
      <button data-t="button">x</button>
      <input data-t="input" />
      <select data-t="select"><option>1</option></select>
      <textarea data-t="textarea"></textarea>
      <div tabindex="0" data-t="div">x</div>
      <span data-t="plain">x</span>
    `);
    expect(labels(root)).toEqual(["link", "button", "input", "select", "textarea", "div"]);
  });

  it("skips everything inside a hidden or inert container", () => {
    const root = mount(`
      <button data-t="visible">x</button>
      <div hidden><button data-t="hidden-button">x</button><textarea data-t="hidden-textarea"></textarea></div>
      <div inert><input data-t="inert-input" /></div>
      <button hidden data-t="hidden-itself">x</button>
    `);
    expect(labels(root)).toEqual(["visible"]);
  });

  it("skips disabled controls, including those disabled by a fieldset", () => {
    const root = mount(`
      <button disabled data-t="disabled-button">x</button>
      <input disabled data-t="disabled-input" />
      <select disabled data-t="disabled-select"></select>
      <textarea disabled data-t="disabled-textarea"></textarea>
      <fieldset disabled><input data-t="fieldset-input" /></fieldset>
      <button data-t="enabled">x</button>
    `);
    expect(labels(root)).toEqual(["enabled"]);
  });

  it("skips tabindex -1 and hidden inputs", () => {
    const root = mount(`
      <button tabindex="-1" data-t="minus-one">x</button>
      <div tabindex="-1" data-t="minus-one-div">x</div>
      <input type="hidden" data-t="hidden-input" />
      <button tabindex="0" data-t="zero">x</button>
    `);
    expect(labels(root)).toEqual(["zero"]);
  });

  it("counts only the checked radio of a group, and every radio of a group with none checked", () => {
    const root = mount(`
      <input type="radio" name="a" data-t="a1" />
      <input type="radio" name="a" data-t="a2" checked />
      <input type="radio" name="a" data-t="a3" />
      <input type="radio" name="b" data-t="b1" />
      <input type="radio" name="b" data-t="b2" />
    `);
    expect(labels(root)).toEqual(["a2", "b1", "b2"]);
  });

  it("is empty for a container with nothing to focus", () => {
    expect(tabbableWithin(mount("<p>text</p>"))).toEqual([]);
  });
});
