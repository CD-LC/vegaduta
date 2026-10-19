// Hands a question from one screen to the research composer (Run again,
// follow-up chips) without putting the question in the URL. sessionStorage is
// per tab and read once; every access is guarded.

const KEY = "vd.research.prefill";

export function setResearchPrefill(question: string): void {
  try {
    window.sessionStorage.setItem(KEY, question);
  } catch {
    // No storage: the composer simply opens empty.
  }
}

/** Read and clear the pending question, or "" when there is none. */
export function takeResearchPrefill(): string {
  try {
    const v = window.sessionStorage.getItem(KEY) ?? "";
    window.sessionStorage.removeItem(KEY);
    return v;
  } catch {
    return "";
  }
}
