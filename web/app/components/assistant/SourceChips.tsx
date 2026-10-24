"use client";

import { ExternalLink } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { ReplySource } from "./sources";

/**
 * The pages a web-search answer drew on, as compact numbered chips under the reply (site name, the title when
 * the result carried one). Each opens in a new tab; nothing renders when the turn touched no web page.
 */
export default function SourceChips({ sources }: { sources: readonly ReplySource[] }) {
  const t = useTranslations("assistant");
  if (sources.length === 0) return null;
  return (
    <nav aria-label={t("sourcesLabel")} className="w-full" data-testid="reply-sources">
      <p className="mb-1 px-0.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {t("sourcesLabel")}
      </p>
      <ol className="flex flex-wrap gap-1.5">
        {sources.map((source, i) => (
          <li key={source.url} className="min-w-0">
            <a
              href={source.url}
              target="_blank"
              rel="noopener noreferrer"
              title={source.title ? `${source.title} - ${source.url}` : source.url}
              className="asst-source group inline-flex max-w-[15rem] items-center gap-1.5 rounded-lg border border-border px-2 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            >
              <span
                className="grid size-4 shrink-0 place-items-center rounded bg-secondary text-[10px] font-semibold tabular-nums text-foreground"
                aria-hidden="true"
              >
                {i + 1}
              </span>
              <span className="min-w-0 truncate">
                <span className="font-medium text-foreground">{source.site}</span>
                {source.title && <span className="hidden sm:inline"> · {source.title}</span>}
              </span>
              <ExternalLink
                size={11}
                className="size-3 shrink-0 opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
                aria-hidden="true"
              />
            </a>
          </li>
        ))}
      </ol>
    </nav>
  );
}
