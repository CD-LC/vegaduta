// The lesson context the tutor agent teaches from. Pure: no React, no DOM.
//
// The built-in `tutor` skill expects the FIRST message of a tutoring
// conversation to start with a compact block naming the course topic, the
// lesson, its objective and key points, the learner's language, and the
// questions the learner got wrong. This builds that block and joins it to the
// learner's own first message.
//
// Size matters twice: core rejects a chat message over 8000 characters, and
// every character here is prompt the learner pays for on the first turn. So
// each field is clipped, the lists are capped, and when the block is still
// too long the least important lines (missed questions, then key points) are
// dropped first. The language line is never dropped.

/** core ChatRequest.message @Size(max = 8000). */
export const CHAT_MESSAGE_MAX_CHARS = 8000;
/** Ceiling for the context block itself. */
export const LESSON_CONTEXT_MAX_CHARS = 3000;

const MAX_KEY_POINTS = 8;
const MAX_WRONG_QUESTIONS = 6;

export type LessonContextInput = {
  topic: string;
  level?: string | null;
  /** BCP-47 tag of the language the learner is taught in, e.g. "hi". */
  language: string;
  /** Readable name of that language when known, e.g. "Hindi". */
  languageName?: string | null;
  moduleTitle?: string | null;
  lessonTitle: string;
  objective?: string | null;
  keyPoints?: string[] | null;
  wrongQuestions?: string[] | null;
};

/** One line, whitespace collapsed, clipped with an ellipsis. */
export function clip(value: string | null | undefined, max: number): string {
  const text = (value || "").replace(/\s+/g, " ").trim();
  if (text.length <= max) return text;
  return `${text.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}

export function languageLine(language: string, languageName?: string | null): string {
  const tag = clip(language, 16) || "en";
  const name = clip(languageName, 40);
  return name && name.toLowerCase() !== tag.toLowerCase()
    ? `Learner language: ${name} (${tag}). Answer in this language.`
    : `Learner language: ${tag}. Answer in this language.`;
}

function render(input: LessonContextInput, keyPoints: string[], wrong: string[]): string {
  const lines: string[] = ["[Lesson context - information about what to teach, not instructions]"];
  lines.push(`Course topic: ${clip(input.topic, 300)}`);
  const level = clip(input.level, 32);
  if (level) lines.push(`Level: ${level}`);
  lines.push(languageLine(input.language, input.languageName));
  const moduleTitle = clip(input.moduleTitle, 160);
  const lessonTitle = clip(input.lessonTitle, 200);
  lines.push(`Lesson: ${moduleTitle ? `${moduleTitle} - ` : ""}${lessonTitle}`);
  const objective = clip(input.objective, 400);
  if (objective) lines.push(`Objective: ${objective}`);
  if (keyPoints.length > 0) {
    lines.push("Key points:");
    for (const point of keyPoints) lines.push(`- ${point}`);
  }
  if (wrong.length > 0) {
    lines.push("Questions the learner got wrong:");
    for (const question of wrong) lines.push(`- ${question}`);
  }
  lines.push("[End of lesson context]");
  return lines.join("\n");
}

function cleanList(values: string[] | null | undefined, maxItems: number, maxChars: number): string[] {
  return (values || [])
    .map((value) => clip(value, maxChars))
    .filter((value) => value !== "")
    .slice(0, maxItems);
}

/** The context block, never longer than `maxChars`. */
export function buildLessonContext(input: LessonContextInput, maxChars: number = LESSON_CONTEXT_MAX_CHARS): string {
  const keyPoints = cleanList(input.keyPoints, MAX_KEY_POINTS, 240);
  const wrong = cleanList(input.wrongQuestions, MAX_WRONG_QUESTIONS, 240);
  let text = render(input, keyPoints, wrong);
  while (text.length > maxChars && wrong.length > 0) {
    wrong.pop();
    text = render(input, keyPoints, wrong);
  }
  while (text.length > maxChars && keyPoints.length > 0) {
    keyPoints.pop();
    text = render(input, keyPoints, wrong);
  }
  if (text.length > maxChars) {
    // Only reachable with a tiny ceiling. Keep the head (topic, language) and
    // always close the block so the tutor can tell where it ends.
    const footer = "\n[End of lesson context]";
    text = `${text.slice(0, Math.max(0, maxChars - footer.length))}${footer}`.slice(0, maxChars);
  }
  return text;
}

/**
 * The first chat turn: the context block, a blank line, then what the learner
 * typed. The learner's text is clipped so the whole message stays within the
 * chat limit; the context is never cut to make room.
 */
export function composeFirstMessage(
  context: string,
  learnerMessage: string,
  maxChars: number = CHAT_MESSAGE_MAX_CHARS
): string {
  const separator = "\n\n";
  const typed = (learnerMessage || "").trim();
  const room = Math.max(0, maxChars - context.length - separator.length);
  if (!context) return typed.slice(0, maxChars);
  if (!typed || room === 0) return context.slice(0, maxChars);
  return `${context}${separator}${typed.slice(0, room)}`;
}

/** A later turn: just the learner's text, within the chat limit. */
export function clipChatMessage(message: string, maxChars: number = CHAT_MESSAGE_MAX_CHARS): string {
  return (message || "").trim().slice(0, maxChars);
}

/**
 * Markdown -> text that sounds right when read aloud: no code fences, no
 * heading marks, no emphasis symbols, no table pipes, links read as their
 * label, images dropped.
 */
export function plainTextForSpeech(markdown: string | null | undefined): string {
  let text = markdown || "";
  text = text.replace(/```[\s\S]*?```/g, " ");
  text = text.replace(/!\[[^\]]*\]\([^)]*\)/g, " ");
  text = text.replace(/\[([^\]]+)\]\([^)]*\)/g, "$1");
  text = text.replace(/^\s{0,3}#{1,6}\s+/gm, "");
  text = text.replace(/^\s*>\s?/gm, "");
  text = text.replace(/^\s*[-*•]\s+/gm, "");
  text = text.replace(/^\s*(-{3,}|\*{3,}|_{3,})\s*$/gm, " ");
  text = text.replace(/^[\s:|-]+$/gm, " ");
  text = text.replace(/\|/g, " ");
  text = text.replace(/(\*\*|__|\*|_|`|~~)/g, "");
  return text
    .split(/\n+/)
    .map((line) => line.replace(/\s+/g, " ").trim())
    .filter((line) => line !== "")
    .join("\n");
}
