import { describe, expect, it } from "vitest";
import { speakableText, stripReasoning } from "./replyText";

describe("stripReasoning", () => {
  it("drops closed and still-open think blocks", () => {
    expect(stripReasoning("<think>hmm</think>The answer is 4.")).toBe("The answer is 4.");
    expect(stripReasoning("Intro <thinking>a</thinking> done")).toBe("Intro  done");
    expect(stripReasoning("<think>still thinking about")).toBe("");
    expect(stripReasoning(null)).toBe("");
  });
});

describe("speakableText", () => {
  it("reads plain words, not markup or LaTeX", () => {
    const out = speakableText("**Result**: $17 \\times 23 = 391$ see [docs](https://x.io) `code`\n```js\nfoo()\n```");
    expect(out).toBe("Result : 17 times 23 = 391 see docs code");
  });
});
