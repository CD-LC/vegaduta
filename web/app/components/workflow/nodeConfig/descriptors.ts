import type { WorkflowNodeConfig } from "./types";

/**
 * Descriptor-driven simple fields: a node whose config is a handful of text or
 * number inputs needs no hand-written form. SimpleFieldsForm renders these.
 * Label/placeholder/hint values are keys in the `nodeConfigDrawer` namespace.
 */
export type SimpleField =
  | { kind: "text"; key: keyof WorkflowNodeConfig; labelKey: string; placeholderKey?: string }
  | { kind: "number"; key: keyof WorkflowNodeConfig; labelKey: string; min: number; max: number; defaultValue: number };

export interface SimpleFormDescriptor {
  fields: SimpleField[];
  hintKey?: string;
}

// Same itemsField contract for Loop and Fan-out (WorkflowNodeType.java's FAN_OUT
// javadoc). maxItems caps at 100 and maxConcurrency at 10, matching
// WorkflowExecutionEngine's ABSOLUTE_MAX_FAN_OUT_* server-side clamps.
export const SIMPLE_FORMS: Record<string, SimpleFormDescriptor> = {
  loop: {
    fields: [
      { kind: "text", key: "itemsField", labelKey: "loopListFieldLabel", placeholderKey: "loopListFieldPlaceholder" },
      { kind: "number", key: "maxIterations", labelKey: "maxIterationsLabel", min: 1, max: 100, defaultValue: 25 },
    ],
    hintKey: "loopHint",
  },
  fan_out: {
    fields: [
      { kind: "text", key: "itemsField", labelKey: "loopListFieldLabel", placeholderKey: "loopListFieldPlaceholder" },
      { kind: "number", key: "maxItems", labelKey: "maxItemsLabel", min: 1, max: 100, defaultValue: 25 },
      { kind: "number", key: "maxConcurrency", labelKey: "maxConcurrencyLabel", min: 1, max: 10, defaultValue: 5 },
    ],
    hintKey: "fanOutHint",
  },
  parallel: { fields: [], hintKey: "parallelHint" },
};
