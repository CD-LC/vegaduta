// What a reply says, for the screen and for the speaker. Some local models write their chain of
// thought into the reply itself between <think> tags instead of sending it as reasoning events, so
// "Hide reasoning" and the reply bubble must both drop it; the speaker also gets plain words, not
// markdown or LaTeX symbols.

/** Removes <think>/<thinking> blocks, including one still open at the end of a streaming reply. */
export function stripReasoning(text: string | null | undefined): string {
  if (!text) return "";
  return text
    .replace(/<(think|thinking)>[\s\S]*?<\/\1>/gi, "")
    .replace(/<(think|thinking)>[\s\S]*$/i, "")
    .replace(/^\s*<\/(think|thinking)>/i, "")
    .trim();
}

const LATEX_WORDS: [RegExp, string][] = [
  [/\\times/g, " times "],
  [/\\div/g, " divided by "],
  [/\\cdot/g, " times "],
  [/\\pm/g, " plus or minus "],
  [/\\approx/g, " approximately "],
  [/\\neq/g, " is not equal to "],
  [/\\leq?/g, " is at most "],
  [/\\geq?/g, " is at least "],
  [/\\sqrt\{([^}]*)\}/g, " square root of $1 "],
  [/\\frac\{([^}]*)\}\{([^}]*)\}/g, " $1 over $2 "],
];

/** Plain text to read aloud: no reasoning, code, links, markdown marks or LaTeX commands. */
export function speakableText(text: string | null | undefined): string {
  let s = stripReasoning(text);
  s = s.replace(/```[\s\S]*?```/g, " ");
  for (const [re, to] of LATEX_WORDS) s = s.replace(re, to);
  return s
    .replace(/\$+/g, " ")
    .replace(/\\[a-zA-Z]+/g, " ")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/https?:\/\/\S+/g, " link ")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/^\s{0,3}#{1,6}\s+/gm, "")
    .replace(/^\s*[-*+]\s+/gm, "")
    .replace(/[*_~|>]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
