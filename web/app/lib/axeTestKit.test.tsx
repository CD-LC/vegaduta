import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { axeViolations } from "./axeTestKit";

afterEach(() => cleanup());

// The a11y tests are only worth something if the checker can fail: prove it flags real problems.
describe("axeViolations", () => {
  it("flags an unlabelled input and an unnamed button", async () => {
    render(
      <div>
        <input type="text" />
        <button type="button" />
      </div>
    );
    const found = await axeViolations();
    expect(found.some((v) => v.startsWith("label"))).toBe(true);
    expect(found.some((v) => v.startsWith("button-name"))).toBe(true);
  });

  it("flags an invalid ARIA role and an image without alternative text", async () => {
    render(
      <div>
        <div {...{ role: "bogus" }}>x</div>
        {/* eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text */}
        <img src="x.png" />
      </div>
    );
    const found = (await axeViolations()).join("\n");
    expect(found).toMatch(/aria-roles/);
    expect(found).toMatch(/image-alt/);
  });

  it("passes a properly labelled control", async () => {
    render(
      <form>
        <label htmlFor="n">Name</label>
        <input id="n" type="text" />
        <button type="submit">Save</button>
      </form>
    );
    expect(await axeViolations()).toEqual([]);
  });
});
