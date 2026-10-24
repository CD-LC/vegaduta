// Readable names for tool calls a person is asked about (approval cards, the approvals queue).
//
// Core records a gated tool call as actionType "mcp_tool.<name>"; an MCP server's tool reaches the agent as
// "mcp__<server>__<tool>". Those are wire names, not something to put in front of a person who is deciding whether
// a call may run. toolLabel() turns them into a short label plus, for built-in tools, one line saying what the call
// will do. The raw name is always returned as well, so a surface can keep it in a tooltip or a details row for
// people who need the exact name. A tool this file does not know keeps its raw name as the label (never a guess).
//
// Pure helpers first (testable without React); useToolLabel() binds them to the "toolLabels" message namespace.
import { useCallback } from "react";
import { useTranslations } from "./i18n";

/** Must stay in sync with GuardedToolCallback.ACTION_TYPE_PREFIX (core). */
const ACTION_PREFIX = "mcp_tool.";

/**
 * Built-in tools with a translated label ("label_<name>") and a "what it will do" line ("does_<name>") in the
 * toolLabels namespace. Adding a name here needs both keys in messages/en.json.
 */
export const KNOWN_TOOL_LABELS = [
  "web_search",
  "fetch_web_page",
  "deep_research",
  "verify_citations",
  "http_request",
  "http_request_write",
  "schedule_task",
  "create_workflow",
  "data_table_query",
  "data_table_write",
  "coding_task",
  "delegate_to_specialist",
  "delegate_parallel",
  "web_task",
] as const;

const KNOWN = new Set<string>(KNOWN_TOOL_LABELS);

export interface ParsedToolName {
  /** The name as given, trimmed (what an expert would search logs for). */
  raw: string;
  /** The tool's own name: no "mcp_tool." prefix, no MCP server part, no trailing "[...]" annotation. */
  tool: string;
  /** The MCP server for an "mcp__<server>__<tool>" name; null otherwise. */
  server: string | null;
}

/**
 * Splits a wire name. Accepts an action type ("mcp_tool.web_search"), a bare tool name ("web_search"), an MCP
 * name ("mcp__github__create_issue", also behind the action prefix) and a summary-derived name that still carries
 * core's annotation ("web_search [queued behind: web_search]").
 */
export function parseToolName(name: string | null | undefined): ParsedToolName {
  const raw = typeof name === "string" ? name.trim() : "";
  let tool = raw.startsWith(ACTION_PREFIX) ? raw.slice(ACTION_PREFIX.length) : raw;
  // Core appends " [queued behind: x]" / " [flagged: reason]" to a summary; neither is part of the name.
  tool = tool.replace(/(\s*\[[^\]]*\])+$/, "").trim();
  const mcp = /^mcp__(.+?)__(.+)$/.exec(tool);
  if (mcp) return { raw, tool: mcp[2], server: mcp[1] };
  return { raw, tool, server: null };
}

export interface ToolLabel {
  /** What to show a person: "Search the web", "create_issue (github)", or the raw name for an unknown tool. */
  label: string;
  /** One short line saying what the call will do; null when the tool is not one this file knows. */
  description: string | null;
  /** The exact wire name, for a tooltip or a details row. */
  raw: string;
}

type Translate = (key: string, values?: Record<string, string | number>) => string;

/** The readable label for a tool or action-type name, using `t` bound to the "toolLabels" namespace. */
export function toolLabel(name: string | null | undefined, t: Translate): ToolLabel {
  const { raw, tool, server } = parseToolName(name);
  if (!tool) return { label: raw, description: null, raw };
  if (server == null && KNOWN.has(tool)) {
    return { label: t(`label_${tool}`), description: t(`does_${tool}`), raw };
  }
  if (server != null) {
    return { label: t("mcpTool", { tool, server }), description: null, raw };
  }
  return { label: tool, description: null, raw };
}

/** toolLabel() bound to the current locale (other locales fall back to English through the i18n provider). */
export function useToolLabel(): (name: string | null | undefined) => ToolLabel {
  const t = useTranslations("toolLabels");
  return useCallback((name: string | null | undefined) => toolLabel(name, t), [t]);
}
