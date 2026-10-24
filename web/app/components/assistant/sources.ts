import type { ActivityStep } from "../agents/AgentActivityTrace";

/**
 * The web pages a reply drew on, for the compact source chips under it (the Perplexity / ChatGPT search
 * pattern). Read from the turn's own step events: the URL a page-reading tool was asked to open (its
 * arguments) and the links a search tool's result excerpt carries (`preview`, already sanitised and capped by
 * core). Only tools that touch the web count, so a file path or an API endpoint in some other tool's output
 * never shows up as a "source". Pure, so it is unit tested without rendering.
 */
export interface ReplySource {
  url: string;
  /** "example.com" */
  site: string;
  /** A title the result excerpt put next to the link, when it had one. */
  title?: string;
}

const WEB_TOOL = /(search|fetch|web|browse|page|research|crawl|scrape|url|news)/i;
const URL_RE = /https?:\/\/[^\s"'<>)\]}]+/g;
const MARKDOWN_LINK_RE = /\[([^\]]{2,120})\]\((https?:\/\/[^\s)]+)\)/g;

function clean(url: string): string | null {
  const trimmed = url.replace(/[.,;:!?'"]+$/, "");
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
    return parsed.toString();
  } catch {
    return null;
  }
}

function siteOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}

/** Unique sources in first-seen order, at most `max`. */
export function replySources(steps: readonly ActivityStep[] | null | undefined, max = 8): ReplySource[] {
  const found = new Map<string, ReplySource>();
  const add = (raw: string, title?: string) => {
    if (found.size >= max) return;
    const url = clean(raw);
    if (!url) return;
    const existing = found.get(url);
    if (existing) {
      if (!existing.title && title) existing.title = title;
      return;
    }
    found.set(url, { url, site: siteOf(url), ...(title ? { title: title.trim() } : {}) });
  };
  for (const step of steps ?? []) {
    if (!step.tool || !WEB_TOOL.test(step.tool)) continue;
    if (step.type !== "tool_call" && step.type !== "tool_result") continue;
    const texts = step.type === "tool_call" ? [step.args] : [step.preview, step.message];
    for (const text of texts) {
      if (!text) continue;
      for (const m of text.matchAll(MARKDOWN_LINK_RE)) add(m[2], m[1]);
      for (const m of text.matchAll(URL_RE)) add(m[0]);
    }
  }
  return [...found.values()];
}
