// Pure editing-side logic for workflow expressions (no React, no DOM). The
// engine's interpreter lives in core; this module only knows enough of the
// syntax to help an author: find {{ }} segments, tokenize for highlighting,
// work out what to autocomplete at the caret, insert data-picker paths and
// rewrite $node["Label"] references when a node is renamed.
//
// Spec: docs/plans/WORKFLOW-EXPRESSIONS-AND-PINNING-SPEC.md. Nothing here may
// throw on malformed input: it runs on every keystroke over half-typed text.//
// The implementation is split by concern; this module is the public entry point.
export { BARE_PREFIX_LEN, ROOTS, ROOT_DOCS, quoteString, unquoteString, wrapBare } from "./expressionLexicon";
export * from "./expressionSyntax";
export * from "./expressionPaths";
export * from "./expressionCompletion";
