// Tests for the local-code structural chunker.
//
// The property these exist to defend is the one the answer UI's citations rest
// on: a chunk's (startLine, endLine) must describe EXACTLY the lines its text
// was cut from. Everything else here — overlap, boundary seams, truncation —
// is secondary to that invariant, so it is asserted on every chunk of every
// fixture rather than in one dedicated test.

import { describe, expect, it } from "vitest";
import {
  BOUNDARY_MIN_FRACTION,
  chunkSource,
  DEFAULT_MAX_CHARS,
  DEFAULT_OVERLAP_LINES,
  DEFAULT_TARGET_LINES,
} from "./chunker";

/** The shortest a boundary-closed chunk is allowed to be, derived the same way
 * the chunker derives it. Spelled out rather than hard-coded so tuning the
 * defaults re-aims these assertions instead of quietly passing for the wrong
 * reason. */
const MIN_BOUNDARY_LINES = Math.max(1, Math.floor(DEFAULT_TARGET_LINES * BOUNDARY_MIN_FRACTION));

/** The invariant, applied to a whole result: text === the lines it claims. */
function expectCitationsAreTrue(source: string, chunks: ReturnType<typeof chunkSource>): void {
  const lines = source.split("\n");
  for (const chunk of chunks) {
    expect(chunk.startLine).toBeGreaterThanOrEqual(1);
    expect(chunk.endLine).toBeGreaterThanOrEqual(chunk.startLine);
    expect(chunk.endLine).toBeLessThanOrEqual(lines.length);
    expect(chunk.text).toBe(lines.slice(chunk.startLine - 1, chunk.endLine).join("\n"));
  }
}

function buildLines(count: number, make: (lineNumber: number) => string): string {
  const out: string[] = [];
  for (let i = 1; i <= count; i++) out.push(make(i));
  return out.join("\n");
}

describe("localcode/chunker", () => {
  it("returns nothing for empty or whitespace-only files", () => {
    expect(chunkSource("src/empty.ts", "")).toEqual([]);
    expect(chunkSource("src/empty.ts", "\n\n   \n\t\n")).toEqual([]);
  });

  it("keeps line numbers true across a long file with declarations", () => {
    // A declaration every 25 lines, indented bodies in between.
    const source = buildLines(200, (n) => (n % 25 === 1 ? `export function fn${n}() {` : `  const x${n} = ${n};`));
    const chunks = chunkSource("src/long.ts", source, "ts");
    expect(chunks.length).toBeGreaterThan(1);
    expectCitationsAreTrue(source, chunks);
    expect(chunks[0].startLine).toBe(1);
    expect(chunks[chunks.length - 1].endLine).toBe(200);
    // Chunks move forward and, together, leave no line uncovered.
    let covered = 0;
    for (let i = 0; i < chunks.length; i++) {
      if (i > 0) expect(chunks[i].startLine).toBeGreaterThan(chunks[i - 1].startLine);
      expect(chunks[i].startLine).toBeLessThanOrEqual(covered + 1);
      covered = Math.max(covered, chunks[i].endLine);
    }
    expect(covered).toBe(200);
  });

  it("overlaps by exactly overlapLines when a chunk is cut by size", () => {
    // Every line is indented, so no top-level-declaration boundary can match
    // and every cut is size-driven.
    const source = buildLines(100, (n) => `  const value${n} = ${n};`);
    const chunks = chunkSource("src/flat.ts", source, "ts");
    expectCitationsAreTrue(source, chunks);
    expect(chunks[0].startLine).toBe(1);
    expect(chunks[0].endLine).toBe(DEFAULT_TARGET_LINES);
    // 40 lines taken, 8 replayed → the next chunk starts at line 33.
    expect(chunks[1].startLine).toBe(DEFAULT_TARGET_LINES - DEFAULT_OVERLAP_LINES + 1);
    for (let i = 1; i < chunks.length; i++) {
      const overlap = chunks[i - 1].endLine - chunks[i].startLine + 1;
      expect(overlap).toBe(DEFAULT_OVERLAP_LINES);
    }
  });

  it("cuts at a declaration without overlap once the chunk is long enough", () => {
    // Past the boundary minimum but before the size ceiling, so the cut is
    // unambiguously the declaration's doing and not the line count's.
    const secondDecl = MIN_BOUNDARY_LINES + 5;
    expect(secondDecl).toBeLessThan(DEFAULT_TARGET_LINES);
    const source = buildLines(60, (n) => {
      if (n === 1) return "export function alpha() {";
      if (n === secondDecl) return "export function beta() {";
      return `  const x${n} = ${n};`;
    });
    const chunks = chunkSource("src/decls.ts", source, "ts");
    expectCitationsAreTrue(source, chunks);
    expect(chunks[0].startLine).toBe(1);
    expect(chunks[0].endLine).toBe(secondDecl - 1);
    // A declaration is a clean seam: the next chunk starts ON it, not before.
    expect(chunks[1].startLine).toBe(secondDecl);
  });

  it("never emits a chunk longer than maxChars — the whole point of the budget", () => {
    // A dense file (long lines, no column-0 declarations) is the case where the
    // character cap, not the line count, has to be what closes the chunk. If
    // this ever fails, the header's "the embedding saw the lines it cites"
    // claim is false again.
    const source = buildLines(120, (n) => `    const ${"veryLongIdentifier".repeat(3)}${n} = compute(${n}, "payload ${n}");`);
    const chunks = chunkSource("src/dense.ts", source, "ts");
    expectCitationsAreTrue(source, chunks);
    expect(chunks.length).toBeGreaterThan(1);
    for (const chunk of chunks) {
      expect(chunk.text.length).toBeLessThanOrEqual(DEFAULT_MAX_CHARS);
      expect(chunk.endLine - chunk.startLine + 1).toBeLessThanOrEqual(DEFAULT_TARGET_LINES);
    }
  });

  it("ignores a declaration that arrives before the minimum chunk length", () => {
    // Declarations every 3 lines: splitting on each would produce 3-line
    // chunks with no context. The size rule wins until minLinesForBoundary.
    const source = buildLines(80, (n) => (n % 3 === 1 ? `export const c${n} = ${n};` : `  // filler ${n}`));
    const chunks = chunkSource("src/barrel.ts", source, "ts");
    expectCitationsAreTrue(source, chunks);
    for (const chunk of chunks) {
      const length = chunk.endLine - chunk.startLine + 1;
      if (chunk.endLine < 80) expect(length).toBeGreaterThanOrEqual(MIN_BOUNDARY_LINES);
    }
  });

  // Explicit sizes here rather than the defaults: what these two pin is the
  // Python boundary matcher and the path-based language fallback, and a fixture
  // whose declaration lands where the DEFAULT line budget can honour it would
  // have to be re-picked every time those defaults are tuned.
  const PY_OPTIONS = { targetLines: 40, overlapLines: 8, maxChars: 4000 };
  const PY_SOURCE = buildLines(70, (n) => {
    if (n === 1) return "def first():";
    if (n === 31) return "class Second:";
    return `    value_${n} = ${n}`;
  });

  it("splits Python on def/class and keeps the line numbers", () => {
    const chunks = chunkSource("app/module.py", PY_SOURCE, "python", PY_OPTIONS);
    expectCitationsAreTrue(PY_SOURCE, chunks);
    expect(chunks.some((c) => c.startLine === 31)).toBe(true);
  });

  it("infers the language from the path when no lang is supplied", () => {
    expect(chunkSource("app/module.py", PY_SOURCE, undefined, PY_OPTIONS)).toEqual(
      chunkSource("app/module.py", PY_SOURCE, "python", PY_OPTIONS)
    );
  });

  it("trims blank lines off the ends so a citation starts on real code", () => {
    const source = "\n\n\nexport const real = 1;\n\n\n";
    const chunks = chunkSource("src/padded.ts", source, "ts");
    expect(chunks).toHaveLength(1);
    expect(chunks[0].startLine).toBe(4);
    expect(chunks[0].endLine).toBe(4);
    expect(chunks[0].text).toBe("export const real = 1;");
  });

  it("caps a minified one-line file at maxChars while keeping the line true", () => {
    const source = `const bundle=${"x".repeat(10_000)};`;
    const chunks = chunkSource("dist/bundle.js", source, "js");
    expect(chunks).toHaveLength(1);
    expect(chunks[0].startLine).toBe(1);
    expect(chunks[0].endLine).toBe(1);
    expect(chunks[0].text.length).toBe(DEFAULT_MAX_CHARS);
    expect(source.startsWith(chunks[0].text)).toBe(true);
  });

  it("honours custom target/overlap options", () => {
    const source = buildLines(30, (n) => `  const value${n} = ${n};`);
    const chunks = chunkSource("src/flat.ts", source, "ts", { targetLines: 10, overlapLines: 3 });
    expectCitationsAreTrue(source, chunks);
    expect(chunks[0].endLine).toBe(10);
    expect(chunks[1].startLine).toBe(8);
  });

  it("gives every chunk of a file a distinct id carrying its real span", () => {
    const source = buildLines(120, (n) => `  const value${n} = ${n};`);
    const chunks = chunkSource("src/flat.ts", source, "ts");
    const ids = new Set(chunks.map((c) => c.id));
    expect(ids.size).toBe(chunks.length);
    for (const chunk of chunks) {
      expect(chunk.id).toBe(`src/flat.ts#L${chunk.startLine}-L${chunk.endLine}`);
      expect(chunk.path).toBe("src/flat.ts");
    }
  });

  it("treats CRLF files exactly like LF ones", () => {
    const lf = buildLines(60, (n) => `  const value${n} = ${n};`);
    const crlf = lf.split("\n").join("\r\n");
    expect(chunkSource("src/flat.ts", crlf, "ts")).toEqual(chunkSource("src/flat.ts", lf, "ts"));
  });
});
