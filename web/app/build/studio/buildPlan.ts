// Step planning for cloud builds of multi-screen apps.
//
// The cloud edit endpoint replies ~1k tokens, so "a CRM with four pages, a
// pipeline board and a rules page" cannot land in one edit - the model
// answers with one small block (often just the <title>) and the studio used
// to report "1 change applied". The cloud leg now asks for a short plan and
// builds it one screen at a time, each step an ordinary edit on the previous
// result. Pure functions only so they are unit-tested.

export const MAX_PLAN_STEPS = 6;

export const PLAN_SYSTEM = [
  "You split a mobile web app idea into build steps for a coding assistant that can only write a small amount of code per step.",
  "Reply with ONLY a numbered list, one step per line, like: 1. <what to build>",
  `At most ${MAX_PLAN_STEPS} steps. Step 1 is the app shell: header, bottom tab bar, the router that switches screens, and the shared data model saved with localStorage.`,
  "Then one step per screen or major feature (a table with search, a board with move buttons, a settings or rules page). Each step must be buildable on its own and keep earlier screens working.",
  "Every screen that shows records must include 5+ realistic sample records saved as the localStorage default AND an Add form for new ones; dashboards compute their numbers from the stored data, never hard-coded.",
  "No commentary, no code.",
].join("\n");

export function planPrompt(description: string): string {
  return `APP IDEA: ${description}`;
}

/** A fast pre-build turn (~150 reply tokens) that decides whether the idea is
 * clear enough to build well or needs one round of questions first. The model
 * answers READY or up to MAX_CLARIFY questions - only things that change what
 * gets built, never styling or tech choices it can pick itself. */
export const MAX_CLARIFY = 3;

export const CLARIFY_SYSTEM = [
  "You scope mobile web app ideas for a builder that gets ONE description and then has to build.",
  "Read the idea. If the essentials are clear enough to build a good first version - what the app does, who it is for, what the main screens show - reply with exactly: READY",
  `If something important genuinely changes what gets built, ask up to ${MAX_CLARIFY} short questions, one per line, no numbering.`,
  "Ask only questions whose answers change the build: what goes on the main screen, what data is tracked, who uses it, which features matter most. NEVER ask about colours, layout, tech stack, or anything you can choose sensibly yourself.",
  "Do not ask what the description already answers. When in doubt, reply READY - a mediocre question wastes the user's time more than a missing detail.",
].join("\n");

export function clarifyPrompt(description: string): string {
  return `APP IDEA: ${description}`;
}

/** READY or a non-question reply means the model wants to build now. Only
 * lines ending in ? count as questions, so a chatty preamble never stalls the
 * build. */
export function parseQuestions(reply: string): string[] {
  const text = (reply || "").trim();
  if (!text || /^\s*READY/i.test(text)) return [];
  const qs = text
    .replace(/\r\n/g, "\n")
    .split("\n")
    .map((l) => l.replace(/^\s*(?:\d+[.)]|[-*•])\s*/, "").replace(/\*\*/g, "").trim())
    .filter((l) => l.length >= 8 && l.endsWith("?"));
  return qs.slice(0, MAX_CLARIFY);
}

/** A detailed brief needs no second look: over ~240 chars the user has done
 * the PM's job already and a clarify turn is pure latency. Under that, a
 * one-line idea genuinely benefits from one question round. */
export function shouldClarify(description: string): boolean {
  return description.trim().length < 240;
}

/** Parse a numbered/bulleted reply into clean steps, capped at MAX_PLAN_STEPS. */
export function parsePlan(reply: string): string[] {
  const steps: string[] = [];
  for (const raw of reply.replace(/\r\n/g, "\n").split("\n")) {
    const m = /^\s*(?:\d+[.)]|[-*•])\s+(.+?)\s*$/.exec(raw);
    if (!m) continue;
    const text = m[1].replace(/\*\*/g, "").trim();
    if (text.length >= 4) steps.push(text.slice(0, 400));
    if (steps.length >= MAX_PLAN_STEPS) break;
  }
  return steps;
}

/** A single-screen idea gains nothing from planning. */
export function needsPlan(description: string): boolean {
  const d = description.toLowerCase();
  if (d.length > 160) return true;
  const screens = (d.match(/\b(page|pages|screen|screens|tab|tabs|dashboard|board|pipeline|workflow|table|kanban)\b/g) || []).length;
  return screens >= 3;
}

export function stepPrompt(description: string, steps: string[], index: number): string {
  const list = steps.map((s, i) => `${i + 1}. ${s}`).join("\n");
  return [
    `You are building this app in ${steps.length} steps: ${description}`,
    `PLAN:\n${list}`,
    `Do ONLY step ${index + 1} now: ${steps[index]}`,
    index === 0
      ? "Replace the placeholder header, content and script. Create the bottom tab bar with a tab for EVERY screen in the plan (later steps fill the screens in), the router, and the data helpers."
      : "Add this screen or feature to the existing app. Keep every earlier screen and the tab bar working; only touch what this step needs. NEVER re-declare a top-level const/let/function that already exists in the script (screens, render, state, ...): extend the existing one instead, and give new helpers new names.",
  ].join("\n\n");
}

/** Names declared more than once at column 0 of the app's script. A later
 * step that re-declares `screens` or `render` throws "Identifier has already
 * been declared" and blanks the whole app (seen on prod with the CRM build). */
export function findDuplicateDeclarations(html: string): string[] {
  const at = html.toLowerCase().lastIndexOf("<script");
  if (at < 0) return [];
  const seen = new Map<string, number>();
  for (const m of html.slice(at).matchAll(/^(?:async\s+)?(?:const|let|var|function)\s+([A-Za-z_$][\w$]*)/gm)) {
    seen.set(m[1], (seen.get(m[1]) || 0) + 1);
  }
  return [...seen].filter(([, n]) => n > 1).map(([name]) => name);
}

export function repairPrompt(names: string[]): string {
  return `The script declares ${names.map((n) => `"${n}"`).join(", ")} more than once at the top level, which throws "Identifier has already been declared" and blanks the app. Merge each duplicate into ONE declaration that keeps the behaviour of all the copies (combine their contents; a render() must render every screen). Change nothing else.`;
}

/** Last step: each step adds its own render function, but the shell's router
 * still points at placeholders, so tapping a tab shows the same screen (seen on
 * prod: every tab showed the Dashboard). */
export function integrationPrompt(description: string, steps: string[]): string {
  return [
    `The app is built in pieces: ${description}`,
    `Screens and features built so far:\n${steps.map((s, i) => `${i + 1}. ${s}`).join("\n")}`,
    "FINAL STEP - wire it together and make it real. Check each screen: (1) lists show 5+ realistic sample records loaded from localStorage defaults and have an Add form that saves a new record; (2) boards (pipeline/kanban) are built from those same records and their move buttons update the saved data; (3) dashboard numbers are computed from the stored data, not hard-coded. Make the router show the REAL screen for every tab by calling that screen's render function (replace any placeholder or empty entry), re-render on hashchange and on first load, and attach each screen's event handlers after it is rendered (search box, forms, move buttons, add/delete). Remove unused placeholders. Do not re-declare existing top-level names.",
  ].join("\n\n");
}
