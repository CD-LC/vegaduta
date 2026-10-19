// Key helpers for the "startFlow" en.json namespace.
//
// Kept as pure functions (not string literals scattered through JSX) so
// copy.test.ts can walk the goal catalogue, build every key the page can ask
// for and assert each one exists in en.json. That test is the guard against
// the failure mode of this app's hand-rolled i18n: a missing key does not fall
// back to English text, it returns the dotted path as the visible string.

import { GOALS, type GoalId } from "./goals";
import type { CardStateKind, MissingItem } from "./prereq";

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export const goalTitleKey = (id: GoalId) => `goal${cap(id)}Title`;
export const goalBodyKey = (id: GoalId) => `goal${cap(id)}Body`;
/** Route goals: what the page it opens will do. */
export const routeBodyKey = (id: GoalId) => `routeBody${cap(id)}`;
/** Route goals: the button that opens the page. */
export const openKey = (id: GoalId) => `open${cap(id)}`;
/** Goals that end in a chat: the sample question put in the box (never sent). */
export const promptKey = (id: GoalId) => `prompt${cap(id)}`;

export const STATUS_KEY: Readonly<Record<CardStateKind, string>> = {
  checking: "statusChecking",
  ready: "statusReady",
  reduced: "statusReduced",
  blocked: "statusBlocked",
  locked: "statusLocked",
};

export const MISSING_NOTE_KEY: Readonly<Record<MissingItem, string>> = {
  provider: "missingProvider",
  "web-search": "missingWebSearch",
  "code-execution": "missingCodeExecution",
  documents: "missingDocuments",
};

export const UNCHECKED_NOTE_KEY: Readonly<Record<MissingItem, string>> = {
  provider: "uncheckedProvider",
  "web-search": "uncheckedWebSearch",
  "code-execution": "uncheckedCodeExecution",
  documents: "uncheckedDocuments",
};

export const SETUP_LINK_KEY: Readonly<Record<MissingItem, string>> = {
  provider: "setupProvider",
  "web-search": "setupWebSearch",
  "code-execution": "setupCodeExecution",
  documents: "setupDocuments",
};

/** Keys with no per-goal or per-item variation. */
export const STATIC_KEYS: readonly string[] = [
  "eyebrow",
  "title",
  "subtitle",
  "stepperLabel",
  "stepGoal",
  "stepCreate",
  "stepTry",
  "stepAnnounce",
  "skipForNow",
  "resumedNote",
  "goalHeading",
  "goalHint",
  "choose",
  "noteTutorOff",
  "lockedNote",
  "seePlans",
  "createHeading",
  "createBody",
  "beforeContinue",
  "createButton",
  "createButtonReduced",
  "creating",
  "createFailed",
  "back",
  "startOver",
  "tryHeading",
  "tryIntro",
  "agentReady",
  "openPlayground",
  "agentGone",
  "openingAgent",
  "celebrationTitle",
  "celebrationBody",
  "celebrationBodyNoTime",
  "nextHeading",
  "nextCustomize",
  "nextDocuments",
  "nextAutomate",
  "nextLearn",
  "tryAnother",
  "elapsedMinSec",
  "elapsedSec",
  "hereTitle",
  "hereBody",
  "hereCta",
  "hereResume",
  "hereDismiss",
];

/** Every key the page can request, derived from the catalogue - what the test checks against en.json. */
export function allStartFlowKeys(): string[] {
  const keys = [...STATIC_KEYS, ...Object.values(STATUS_KEY)];
  for (const item of Object.keys(MISSING_NOTE_KEY) as MissingItem[]) {
    keys.push(MISSING_NOTE_KEY[item], UNCHECKED_NOTE_KEY[item], SETUP_LINK_KEY[item]);
  }
  for (const goal of GOALS) {
    keys.push(goalTitleKey(goal.id), goalBodyKey(goal.id));
    if (goal.kind === "route") keys.push(routeBodyKey(goal.id), openKey(goal.id));
    if (goal.kind === "agent" || goal.fallbackTemplateKey) keys.push(promptKey(goal.id));
  }
  return keys;
}
