// Plain constants + pure functions only, no "use client" and no Next imports:
// shared by app/robots.ts (who is allowed) and proxy.js (who actually showed
// up), the same directive-free arrangement as i18n-config.ts.
//
// GEO pass, 2026-09-19.

// Robots.txt tokens of AI crawlers this site explicitly admits. The policy and
// the reasoning live in app/robots.ts; this is only the list.
export const AI_CRAWLERS: string[] = [
  // OpenAI — https://developers.openai.com/api/docs/bots
  "GPTBot", // training
  "OAI-SearchBot", // ChatGPT Search index: decides whether an answer can cite us
  "ChatGPT-User", // fetch triggered by a person's question
  // Anthropic
  "ClaudeBot",
  "Claude-SearchBot",
  "Claude-User",
  // Perplexity — https://docs.perplexity.ai/docs/resources/perplexity-crawlers
  "PerplexityBot",
  "Perplexity-User",
  // Robots TOKENS, not crawlers: they never appear in a User-Agent header.
  // They govern AI use of pages the vendor's main bot already fetched.
  "Google-Extended",
  "Applebot-Extended",
  // Others that publish a token
  "CCBot",
  "Amazonbot",
  "Meta-ExternalAgent",
  "DuckAssistBot",
  "MistralAI-User",
];

// Matching is a case-insensitive substring test because that is how the
// vendors tell you to identify them: the token sits inside a longer UA string
// ("Mozilla/5.0 … compatible; GPTBot/1.2; +https://openai.com/gptbot").
// Longer tokens first, so "Claude-SearchBot" is not reported as "ClaudeBot"'s
// shorter cousin and "Perplexity-User" is not swallowed by a looser match.
const UA_TOKENS = AI_CRAWLERS.filter((t) => !t.endsWith("-Extended")).sort(
  (a, b) => b.length - a.length
);

/** The AI crawler a User-Agent belongs to, or null. */
export function classifyAiCrawler(userAgent: string | null | undefined): string | null {
  if (!userAgent) return null;
  const ua = userAgent.toLowerCase();
  return UA_TOKENS.find((token) => ua.includes(token.toLowerCase())) || null;
}

// Assistants that send a visitor our way. Matched against the Referer HOST
// only — never the path or query, which can carry a person's prompt.
const AI_REFERRER_HOSTS: Record<string, string> = {
  "chatgpt.com": "chatgpt",
  "chat.openai.com": "chatgpt",
  "perplexity.ai": "perplexity",
  "www.perplexity.ai": "perplexity",
  "gemini.google.com": "gemini",
  "copilot.microsoft.com": "copilot",
  "claude.ai": "claude",
  "you.com": "you",
  "chat.mistral.ai": "mistral",
  "www.phind.com": "phind",
  "kagi.com": "kagi",
};

/**
 * Which assistant referred this request, or null. Two signals, because each
 * fails in a different way: the Referer header is stripped by most in-app
 * browsers, and `utm_source=chatgpt.com` (appended by ChatGPT to the links it
 * shows) survives that but only ChatGPT sends it.
 */
export function classifyAiReferral(
  referer: string | null | undefined,
  utmSource: string | null | undefined
): string | null {
  if (utmSource) {
    const viaUtm = AI_REFERRER_HOSTS[utmSource.toLowerCase()];
    if (viaUtm) return viaUtm;
  }
  if (!referer) return null;
  try {
    return AI_REFERRER_HOSTS[new URL(referer).hostname.toLowerCase()] || null;
  } catch {
    return null;
  }
}
