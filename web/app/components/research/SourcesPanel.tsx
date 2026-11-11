"use client";

import { useEffect, useMemo, useState } from "react";
import { ExternalLink } from "lucide-react";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import { safeSourceUrl } from "./citeLinks";
import { RS } from "./researchStrings";
import { hostOf, verdictLabelKey, verdictTone, type VerdictTone } from "./researchRun";
import { domainSummary, filterGroups, groupIndexById, quoteToneCounts, type SourceGroup } from "./reportStructure";

const TONE_CLASS: Record<VerdictTone, string> = {
  supported: "border-[color-mix(in_srgb,var(--state-success)_40%,transparent)] text-[var(--state-success)]",
  weak: "border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] text-[var(--state-warning)]",
  unsupported: "border-destructive/40 text-destructive",
  unknown: "border-muted-foreground/30 text-muted-foreground",
};

const TONE_LABEL: Record<VerdictTone, string> = {
  supported: RS.toneSupported,
  weak: RS.toneWeak,
  unsupported: RS.toneUnsupported,
  unknown: RS.toneUnknown,
};
const TONES: VerdictTone[] = ["supported", "weak", "unsupported", "unknown"];

export const sourceAnchorId = (index: number) => `research-source-${index}`;

function Chip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`rounded-full border px-2.5 py-1 text-xs transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring ${
        active ? "border-primary bg-primary/10 text-foreground" : "border-border text-muted-foreground hover:bg-muted"
      }`}
    >
      {children}
    </button>
  );
}

/**
 * The sources of a report: de-duplicated by page, with a small overview
 * (pages and domains, how the citation check judged each quote) that doubles
 * as filters, and one card per page with its quotes. A citation chip in the
 * report body asks this panel to show and highlight the card it points at
 * (`jump`); an active filter is cleared first so the card is never hidden.
 *
 * The overview only counts what the result contains - it is not a quality
 * score. The citation check compares wording, which the existing note says.
 */
export default function SourcesPanel({
  groups,
  sourceCount,
  jump,
}: {
  groups: ReadonlyArray<SourceGroup>;
  /** Raw number of sources in the result (shown in the heading). */
  sourceCount: number;
  jump: { id: string; n: number } | null;
}) {
  const t = useTranslations("researchPage");
  const [domain, setDomain] = useState<string | null>(null);
  const [tone, setTone] = useState<VerdictTone | null>(null);
  const [flash, setFlash] = useState<number | null>(null);

  const domains = useMemo(() => domainSummary(groups), [groups]);
  const tones = useMemo(() => quoteToneCounts(groups), [groups]);
  const indexById = useMemo(() => groupIndexById(groups), [groups]);
  const shown = useMemo(() => filterGroups(groups, { domain, tone }), [groups, domain, tone]);
  const filtered = domain !== null || tone !== null;

  useEffect(() => {
    if (!jump) return undefined;
    const idx = indexById.get(jump.id);
    if (idx === undefined) return undefined;
    setDomain(null);
    setTone(null);
    setFlash(idx);
    const scroll = setTimeout(() => {
      const el = document.getElementById(sourceAnchorId(idx));
      if (el) {
        let reduce = false;
        try {
          reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
        } catch {
          /* default motion */
        }
        el.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "center" });
        el.focus({ preventScroll: true });
      }
    }, 30);
    const clear = setTimeout(() => setFlash(null), 2600);
    return () => {
      clearTimeout(scroll);
      clearTimeout(clear);
    };
  }, [jump, indexById]);

  return (
    <Card className="gap-3 p-5" data-testid="research-sources">
      <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {t("sourcesHeading", { count: sourceCount })}
      </p>
      <p className="text-xs text-muted-foreground">{t("lexicalNote")}</p>

      {groups.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("sourcesEmpty")}</p>
      ) : (
        <>
          <div className="space-y-2 rounded-lg border border-border p-3" data-testid="research-source-overview">
            <p className="text-xs font-medium text-foreground">
              {RS.sourcesOverview}: {RS.sourcesPages(groups.length, domains.length)}
            </p>
            {TONES.some((k) => tones[k] > 0) && (
              <div role="group" aria-label={RS.filterByCheck} className="flex flex-wrap gap-1.5">
                {TONES.filter((k) => tones[k] > 0).map((k) => (
                  <Chip key={k} active={tone === k} onClick={() => setTone(tone === k ? null : k)}>
                    <span
                      className={
                        tone === k
                          ? undefined
                          : TONE_CLASS[k]
                              .split(" ")
                              .filter((c) => c.startsWith("text-"))
                              .join(" ")
                      }
                    >
                      {TONE_LABEL[k]}
                    </span>{" "}
                    <span className="tabular-nums">{tones[k]}</span>
                  </Chip>
                ))}
              </div>
            )}
            {domains.length > 1 && (
              <div role="group" aria-label={RS.filterByDomain} className="flex flex-wrap gap-1.5">
                {domains.map((d) => (
                  <Chip
                    key={d.domain}
                    active={domain === d.domain}
                    onClick={() => setDomain(domain === d.domain ? null : d.domain)}
                  >
                    {d.domain} <span className="tabular-nums">{d.count}</span>
                  </Chip>
                ))}
              </div>
            )}
            {filtered && (
              <button
                type="button"
                className="text-xs text-primary underline underline-offset-2"
                onClick={() => {
                  setDomain(null);
                  setTone(null);
                }}
              >
                {RS.clearFilters}
              </button>
            )}
          </div>

          {shown.length === 0 ? (
            <p className="text-sm text-muted-foreground" role="status">
              {RS.noSourceMatch}
            </p>
          ) : (
            <ol className="space-y-4">
              {shown.map((g) => {
                const idx = groups.findIndex((x) => x.url === g.url);
                const href = safeSourceUrl(g.url);
                return (
                  <li
                    key={`${g.url}-${idx}`}
                    id={sourceAnchorId(idx)}
                    tabIndex={-1}
                    className={`scroll-mt-24 space-y-2 rounded-md outline-none transition-shadow ${
                      flash === idx ? "ring-2 ring-primary ring-offset-4 ring-offset-card" : ""
                    }`}
                  >
                    <div className="flex flex-wrap items-baseline gap-2">
                      {g.ids.map((id) => (
                        <Badge key={id} variant="secondary" className="font-mono">
                          {id}
                        </Badge>
                      ))}
                      <span className="text-sm font-medium text-foreground [overflow-wrap:anywhere]">
                        {g.title || (href ? hostOf(href) : t("sourceUntitled"))}
                      </span>
                    </div>
                    {g.title && href && <p className="text-xs text-muted-foreground">{g.domain}</p>}
                    {href ? (
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer nofollow"
                        className="inline-flex items-center gap-1 text-xs text-primary underline underline-offset-2 [overflow-wrap:anywhere]"
                      >
                        {g.url} <ExternalLink className="size-3 shrink-0" aria-hidden="true" />
                      </a>
                    ) : (
                      // Not an http/https address - shown, never linked.
                      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{g.url}</span>
                    )}
                    {g.quotes.map((q, qi) => {
                      const qt = verdictTone(q.verdict);
                      const labelKey = verdictLabelKey(q.verdict);
                      return (
                        <div key={qi} className="space-y-1 border-s-2 border-border ps-3">
                          <p className="text-sm italic text-foreground [overflow-wrap:anywhere]">“{q.quote}”</p>
                          {q.claim && (
                            <p className="text-xs text-muted-foreground">
                              {t("claimLabel")} {q.claim}
                            </p>
                          )}
                          <Badge variant="outline" className={TONE_CLASS[qt]}>
                            {labelKey ? t(labelKey) : q.verdict || t("verdictNotChecked")}
                          </Badge>
                        </div>
                      );
                    })}
                  </li>
                );
              })}
            </ol>
          )}
        </>
      )}
    </Card>
  );
}
