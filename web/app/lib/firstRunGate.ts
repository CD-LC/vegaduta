// One first-run modal at a time (2026-10-09: on a brand-new login the language
// prompt and the product-tour welcome opened stacked on top of each other).
// The language prompt goes first - the locale it sets changes every string the
// tour then shows - so it marks itself pending from mount until it either
// decides there is nothing to ask or the visitor answers, and the tour waits.
// Module state, not React state: the two components live in different trees.

let languagePromptPending = false;

export function setLanguagePromptPending(pending: boolean): void {
  languagePromptPending = pending;
}

export function isLanguagePromptPending(): boolean {
  return languagePromptPending;
}
