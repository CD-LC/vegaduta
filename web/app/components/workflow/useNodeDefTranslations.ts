"use client";

import { useMemo } from "react";
import { useTranslations } from "../../lib/i18n";
import { isDataNodeDefKey } from "./dataNodes";
import { isAiNodeDefKey } from "./aiNodes";

// Scoped translator for everything NODE_DEFS-derived (nodeLabelKey /
// nodeDescriptionKey / categoryLabelKey). Almost every key lives in the
// long-standing "nodeDefs" en.json namespace; the HTTP Request node's two
// palette strings live in the newer "httpNode" namespace instead (added as
// its own top-level block so the feature's strings ship as one self-contained
// unit rather than edits scattered through an existing namespace). Consumers
// call this exactly like useTranslations("nodeDefs") and never need to know
// which namespace a given key resolves from.
const HTTP_NODE_DEF_KEYS = new Set(["httpLabel", "httpDescription"]);
// The four Dify-style AI nodes' palette strings (question_classifier, parameter_extractor, variable_aggregator,
// document_extractor) live in the "workflowNodesAi" namespace the same way (see aiNodes.isAiNodeDefKey).

// The eight data nodes (set, filter, sort, aggregate, limit, dedupe, switch, merge) and their "Data" palette
// category keep ALL their strings - palette, card subtitles and config forms - in the one "workflowNodesData"
// namespace, so the feature ships as a single self-contained en.json block (see dataNodes.ts isDataNodeDefKey).
// Same arrangement for the Wait node: its palette strings live in the "workflowForms" namespace (the wfcontrol
// feature's own block, which also carries the form-trigger, error-strategy and Wait editor strings).
const WORKFLOW_FORMS_NODE_DEF_KEYS = new Set(["waitLabel", "waitDescription"]);

export type NodeDefTranslator = (key: string, values?: Record<string, string | number>) => string;

export function useNodeDefTranslations(): NodeDefTranslator {
  const tNodeDefs = useTranslations("nodeDefs");
  const tHttpNode = useTranslations("httpNode");
  const tData = useTranslations("workflowNodesData");
  const tAiNodes = useTranslations("workflowNodesAi");
  const tWorkflowForms = useTranslations("workflowForms");
  return useMemo<NodeDefTranslator>(
    () => (key, values) =>
      HTTP_NODE_DEF_KEYS.has(key)
        ? tHttpNode(key, values)
        : isDataNodeDefKey(key)
          ? tData(key, values)
          : isAiNodeDefKey(key)
            ? tAiNodes(key, values)
            : WORKFLOW_FORMS_NODE_DEF_KEYS.has(key)
              ? tWorkflowForms(key, values)
              : tNodeDefs(key, values),
    [tNodeDefs, tHttpNode, tData, tAiNodes, tWorkflowForms]
  );
}
