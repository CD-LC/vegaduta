"use client";

// The answer surface: streamed text while the on-device model is generating,
// then the citation row.
//
// Citations are the honesty mechanism for a small local model — it is much
// weaker than a hosted one, so "here are the exact chunks I read" is what
// makes a wrong answer cheap to catch. Clicking one expands the retrieved
// source INLINE rather than opening a file: nothing in this feature may leave
// the browser, and re-reading the file from disk would show today's bytes
// rather than the bytes the answer was actually grounded in.
//
// This card renders exactly three finished shapes, and they come straight from
// askLocalCode's contract rather than from guesses about it:
//
//   answered   — ranLocally === true. By contract that means a non-empty
//                answer AND the exact excerpts that went into the prompt. At
//                least one excerpt, always: ask.ts builds that branch only
//                after fitChunks kept something, and returns unanswered when
//                it kept nothing. So there is no empty-citation branch below —
//                the "no matching code was found" line that used to sit there
//                was unreachable, and would have blamed the person's repo for
//                a state the ask layer cannot produce.
//   stopped    — the person pressed Stop. askLocalCode resolves rather than
//                throwing, so the tokens already streamed are still ours to
//                show; they are cut off mid-thought and are labelled as such,
//                and there are no citations to list because the call returned
//                none.
//   unanswered — ranLocally === false. ask.ts emits that ONLY from its
//                unanswered() helper, which always carries answer:"",
//                citations:[] and a `detail` naming what to do next ("no
//                on-device model is ready", "even the closest excerpt is too
//                long for the context window", …). So the detail IS the
//                answer here and gets rendered as the body. The earlier
//                "Retrieved only" badge over "The model returned nothing"
//                claimed retrieval had happened and blamed the model, and
//                both halves were wrong in every case ask.ts can produce.

import { FileCode2, Loader2 } from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import type { RetrievedChunk } from "../../lib/localcode/types";
import type { LocalAnswer } from "../../lib/localcode/ask";

/** "app/lib/api.ts:120" — the shape people paste into an editor's go-to-line. */
function citationLabel(chunk: RetrievedChunk): string {
  return `${chunk.path}:${chunk.startLine}`;
}

export default function LocalCodeAnswer({
  streamed,
  answer,
  running,
  stoppedDetail,
  error,
  selectedCitationId,
  onSelectCitation,
}: {
  /** Tokens received so far. Shown while `running`; once the call resolves,
   * `answer.answer` is the source of truth (a model can rewrite/trim its own
   * final text, and the streamed buffer is only ever a preview of it) —
   * except on the stopped path, where there is no final text and the buffer
   * is all there is. */
  streamed: string;
  answer: LocalAnswer | null;
  running: boolean;
  /** Non-null once the person pressed Stop: askLocalCode's own reason for the
   * halt. Its presence is what keeps this card mounted over the partial text
   * instead of the whole thing vanishing the moment `running` goes false. */
  stoppedDetail: string | null;
  error: string | null;
  selectedCitationId: string | null;
  onSelectCitation: (chunk: RetrievedChunk) => void;
}) {
  const t = useTranslations("localCode");

  if (error) {
    return (
      <div role="alert" className="rounded-lg border border-border bg-card p-4 text-sm text-destructive">
        {error}
      </div>
    );
  }
  if (!running && !answer && stoppedDetail === null) return null;

  const stopped = !running && stoppedDetail !== null;
  // Explicit literal comparison, not truthiness: this project compiles with
  // "strict": false, where only the `=== true` form narrows reliably.
  const answered = !running && answer !== null && answer.ranLocally === true;
  const unanswered = !running && answer !== null && answer.ranLocally !== true;

  // Citations are shown for a FINISHED local answer and nothing else. On the
  // other two paths askLocalCode returns an empty array, and printing "no
  // matching code was found" over it would assert a retrieval that either
  // never ran or never got to report.
  const citations = answered && answer ? answer.citations : [];
  const selected = citations.find((c) => c.id === selectedCitationId) || null;

  let body = "";
  if (running) body = streamed;
  else if (stopped) body = streamed;
  else if (answered && answer) body = answer.answer;
  else if (answer) body = answer.detail || t("answer.empty");

  return (
    <div className="rounded-lg border border-border bg-card">
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-2.5">
        <span className="text-sm font-medium text-foreground">{t("answer.title")}</span>
        {running && (
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <Loader2 size={11} className="animate-spin" aria-hidden="true" />
            {t("answer.thinking")}
          </span>
        )}
        {!running && (answered || unanswered || stopped) && (
          <span
            className={cn(
              "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium",
              answered ? "bg-primary/10 text-primary" : "bg-secondary text-muted-foreground"
            )}
          >
            {answered ? t("answer.ranLocally") : stopped ? t("answer.stopped") : t("answer.notAnswered")}
          </span>
        )}
      </div>

      <div className="px-4 py-3">
        {body ? (
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground">{body}</p>
        ) : (
          // Stopped with nothing streamed: askLocalCode's own STOPPED detail
          // IS the whole message, so it takes the body slot rather than being
          // demoted under a second sentence that says the same thing.
          <p className="text-sm text-muted-foreground">{stopped ? stoppedDetail : t("answer.empty")}</p>
        )}
        {stopped && body && (
          <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">{t("answer.stoppedKept")}</p>
        )}
        {/* A finished local answer's detail is supplementary. The unanswered
            path already printed its detail as the body above, so repeating it
            here would say the same sentence twice. */}
        {answered && answer && answer.detail && (
          <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">{answer.detail}</p>
        )}
      </div>

      {/* Gated on having something to list rather than on `answered` alone: a
          finished answer always carries excerpts (see the header), so this is
          only ever the same condition — but written this way, a contract that
          ever slipped would drop the section instead of printing an empty
          "Sources" heading or a sentence inventing a reason for it. */}
      {answered && citations.length > 0 && (
        <div className="border-t border-border px-4 py-3">
          <div className="mb-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            {t("answer.citations")}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {citations.map((chunk) => (
              <button
                key={chunk.id}
                type="button"
                onClick={() => onSelectCitation(chunk)}
                aria-pressed={selectedCitationId === chunk.id}
                className={cn(
                  "inline-flex items-center gap-1.5 rounded-md border px-2 py-1 font-mono text-[11px] transition-colors",
                  selectedCitationId === chunk.id
                    ? "border-primary bg-primary/10 text-foreground"
                    : "border-border bg-transparent text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
                )}
              >
                <FileCode2 size={11} className="shrink-0" aria-hidden="true" />
                {citationLabel(chunk)}
              </button>
            ))}
          </div>

          {selected && (
            <div className="mt-3 overflow-hidden rounded-md border border-border">
              <div className="flex items-center justify-between gap-2 bg-secondary/50 px-3 py-1.5 text-[11px] text-muted-foreground">
                <span className="truncate font-mono">
                  {selected.path}:{selected.startLine}-{selected.endLine}
                </span>
                <span className="shrink-0">{t("answer.score", { score: selected.score.toFixed(2) })}</span>
              </div>
              <pre className="max-h-64 overflow-auto px-3 py-2 font-mono text-[11px] leading-relaxed text-muted-foreground">
                {selected.text}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
