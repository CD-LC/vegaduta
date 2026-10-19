// Agent Lab - Prompt Knockout's splitter. Cuts a system prompt into a small
// number of sections that can each be left out in turn. Pure text work, no
// React and no network.
//
// The partition is LOSSLESS: joining every section's `text` gives the prompt
// back character for character, so "the prompt without section 3" is always
// the real prompt minus exactly that text and nothing else.

export interface PromptSection {
  id: string;      // "s1" … "sN", in prompt order
  index: number;   // 0-based position
  title: string;   // heading text, else the first words; "" when there is none
  text: string;    // the exact slice of the prompt, separators included
  start: number;   // offset of `text` in the prompt
  end: number;     // exclusive
}

/** More sections than this cost more model calls than one run may make. */
export const MAX_PROMPT_SECTIONS = 12;
/** A fragment shorter than this is a heading or a stray line, not a section. */
export const MIN_FRAGMENT_CHARS = 40;

const HEADING_RE = /^#{1,6}\s/;
// "Tone:" / "Escalation rules:" alone on a line - a plain-text heading.
const LABEL_RE = /^[A-Z][^\n]{1,60}:\s*$/;
const TITLE_WORDS = 6;

interface Range { start: number; end: number }

function titleOf(text: string): string {
  const firstLine = text.split("\n").map((line) => line.trim()).find((line) => line !== "") ?? "";
  if (HEADING_RE.test(firstLine)) {
    const heading = firstLine.replace(/^#{1,6}\s+/, "").replace(/\s+#+$/, "").trim();
    if (heading) return heading;
  }
  if (LABEL_RE.test(firstLine)) return firstLine.replace(/:\s*$/, "").trim();
  const words = text.trim().split(/\s+/).filter(Boolean);
  if (words.length <= TITLE_WORDS) return words.join(" ");
  return words.slice(0, TITLE_WORDS).join(" ") + "…";
}

/**
 * Splits at markdown headings, label lines and blank-line paragraph breaks,
 * folds fragments too small to stand alone into the section after them, then
 * merges the smallest neighbours until at most `maxSections` remain
 * (`merged` reports that this happened).
 */
export function splitPromptSections(
  prompt: string,
  maxSections: number = MAX_PROMPT_SECTIONS
): { sections: PromptSection[]; merged: boolean } {
  if (prompt === "") return { sections: [], merged: false };
  const cap = Math.max(1, Math.floor(maxSections));

  // 1. Raw fragments. Blank lines stay with the fragment BEFORE them, so a
  //    section's text starts at its first real line.
  const raw: Range[] = [];
  let fragmentStart = 0;
  let hasContent = false;      // the open fragment holds a non-blank line
  let bareHeading = false;     // ...and that line is only a heading so far
  let previousBlank = false;
  for (const match of prompt.matchAll(/[^\n]*\n|[^\n]+$/g)) {
    const offset = match.index ?? 0;
    const line = match[0].replace(/\r?\n$/, "");
    const blank = line.trim() === "";
    const heading = !blank && (HEADING_RE.test(line) || LABEL_RE.test(line));
    // A paragraph break straight after a heading is not a boundary: the
    // heading belongs to the text it introduces.
    const paragraphBreak = !blank && previousBlank && !bareHeading;
    if (hasContent && (heading || paragraphBreak)) {
      raw.push({ start: fragmentStart, end: offset });
      fragmentStart = offset;
      hasContent = false;
      bareHeading = false;
    }
    if (!blank) {
      bareHeading = heading && !hasContent;
      hasContent = true;
    }
    previousBlank = blank;
  }
  raw.push({ start: fragmentStart, end: prompt.length });

  // 2. Fold tiny fragments forward; a tiny last one has nothing after it and
  //    joins the section before it instead.
  const tiny = (range: Range) => prompt.slice(range.start, range.end).trim().length < MIN_FRAGMENT_CHARS;
  const ranges: Range[] = [];
  let carry: number | null = null;
  raw.forEach((fragment, i) => {
    const range = { start: carry ?? fragment.start, end: fragment.end };
    if (i < raw.length - 1 && tiny(range)) {
      carry = range.start;
      return;
    }
    ranges.push(range);
    carry = null;
  });
  if (ranges.length > 1 && tiny(ranges[ranges.length - 1])) {
    const last = ranges.pop() as Range;
    ranges[ranges.length - 1].end = last.end;
  }

  // 3. Merge to the cap, smallest adjacent pair first.
  let merged = false;
  while (ranges.length > cap) {
    let at = 0;
    let smallest = Infinity;
    for (let i = 0; i < ranges.length - 1; i++) {
      const combined = ranges[i + 1].end - ranges[i].start;
      if (combined < smallest) {
        smallest = combined;
        at = i;
      }
    }
    ranges.splice(at, 2, { start: ranges[at].start, end: ranges[at + 1].end });
    merged = true;
  }

  const sections = ranges.map((range, index): PromptSection => {
    const text = prompt.slice(range.start, range.end);
    return { id: `s${index + 1}`, index, title: titleOf(text), text, start: range.start, end: range.end };
  });
  return { sections, merged };
}

/**
 * The prompt with the given sections left out. Removing a section can leave
 * a run of blank lines behind, so 3+ newlines collapse to one blank line and
 * the ends are trimmed. `promptWithout(sections, [])` is the baseline: it gets
 * the same tidy-up as every variant, so the only difference between them is
 * the removed text.
 */
export function promptWithout(sections: readonly PromptSection[], removedIds: readonly string[]): string {
  return sections
    .filter((section) => !removedIds.includes(section.id))
    .map((section) => section.text)
    .join("")
    .replace(/(?:\r?\n){3,}/g, (run) => (run.startsWith("\r") ? "\r\n\r\n" : "\n\n"))
    .trim();
}
