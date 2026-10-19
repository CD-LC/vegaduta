// English wording for the research screens' newer controls.
//
// These are plain constants on purpose: web/messages/*.json is owned
// elsewhere and the `t(key) || fallback` idiom does not work in this repo
// (t() returns the key path for a missing key, which is truthy). When these
// strings are moved into the message catalogs, replace each constant with a
// t("researchPage.<key>") call - nothing else about the components changes.

export const RS = {
  // composer
  examplesLabel: "Not sure where to start? Try one:",
  examples: [
    "What does current evidence say about the four-day work week and productivity?",
    "Compare the main open-source vector databases for a small team: cost, scaling and maintenance.",
    "What are the most credible explanations for the rise in global coffee prices?",
    "Summarise the current state of research on intermittent fasting, including what is still disputed.",
  ],
  dictate: "Dictate",
  dictateStop: "Stop dictation",
  dictating: "Listening. Speak your question.",
  dictateError: "Dictation stopped. Check microphone permission and try again.",
  submitHint: "Tip: press Ctrl+Enter (Cmd+Enter on Mac) to start.",
  charsLeft: (n: number) => `${n} characters left`,

  // history
  historyItemLabel: "research runs",
  historySearch: "Search your research",
  historySearchPlaceholder: "Search questions on this page",
  historyFilterAll: "All",
  historyFilterRunning: "In progress",
  historyFilterDone: "Done",
  historyFilterIssues: "Failed or cancelled",
  historyNoMatch: "No runs on this page match.",
  historyClearFilters: "Clear search and filter",
  historyPin: "Pin this run",
  historyUnpin: "Unpin this run",
  historyLoading: "Loading your research…",
  historyFilterNote: "Search and filters apply to the runs loaded on this page.",

  // run view
  runAgain: "Run again",
  runAgainHint: "Opens the question box with this question filled in so you can edit it first.",
  statusAnnouncement: (label: string) => `Research status: ${label}`,
  pollingStalled: "Updates paused after repeated failures.",
  pollingRetry: "Check again",

  // report
  readingTime: (min: number) => `${min} min read`,
  contents: "On this page",
  followUpsHeading: "Keep digging",
  followUpsNote: "Each one opens the question box, filled in, so you can edit it before running.",
  refine: "Refine this question",
  hoverGoToSource: "Click to jump to the source",
  sourcesOverview: "Source overview",
  sourcesPages: (pages: number, domains: number) =>
    `${pages} ${pages === 1 ? "page" : "pages"} from ${domains} ${domains === 1 ? "domain" : "domains"}`,
  filterByDomain: "Filter by domain",
  filterByCheck: "Filter by citation check",
  clearFilters: "Clear filters",
  toneSupported: "Supported",
  toneWeak: "Weak support",
  toneUnsupported: "Not supported",
  toneUnknown: "Not checked",
  noSourceMatch: "No sources match these filters.",
  alsoCitedAs: "Also cited as",

  // export
  exportFootnoted: "Markdown (footnotes)",
  exportPrint: "Print / PDF",
  exportShare: "Share",
  exportCopyLink: "Copy link",
  exportLinkCopied: "Link copied",
  exportLinkNote: "The link opens this run for you only; other people cannot open it.",
  exportShareFailed: "Sharing was cancelled or is not available here.",
} as const;
