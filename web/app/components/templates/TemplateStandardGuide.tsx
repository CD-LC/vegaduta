"use client";

import { useEffect, useState } from "react";
import { BookOpenCheck, Copy, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import type { TemplateStandardGuide as Guide } from "../../lib/api";
import { toast } from "../../lib/toastBus";

// The complete instruction set for authors: how sharing works, the principles, every rule (with how to fix it)
// and a worked example they can copy. Loaded from the SAME standard the validator and the AI reviewer use,
// so what is shown here is exactly what will be checked.
export default function TemplateStandardGuide({ defaultOpen = false }: { defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const [guide, setGuide] = useState<Guide | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!open || guide) return undefined;
    let cancelled = false;
    api
      .getTemplateStandard()
      .then((g) => !cancelled && setGuide(g))
      .catch(() => !cancelled && setError(true));
    return () => {
      cancelled = true;
    };
  }, [open, guide]);

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast.success("Copied.");
    } catch {
      toast.error("Couldn't copy - select the text and copy it manually.");
    }
  }

  return (
    <details
      className="rounded-lg border border-border bg-card"
      open={open}
      onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
      data-testid="template-standard-guide"
    >
      <summary className="flex cursor-pointer items-center gap-2 px-3 py-2.5 text-sm font-medium text-foreground">
        <BookOpenCheck size={15} strokeWidth={2} aria-hidden />
        Read the full template guide
        <span className="ml-auto text-xs font-normal text-muted-foreground">what we check, and how to pass</span>
      </summary>
      <div className="max-h-[50vh] space-y-5 overflow-y-auto border-t border-border px-4 py-4 text-sm">
        {!guide && !error && (
          <p className="flex items-center gap-2 text-muted-foreground">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" /> Loading the guide…
          </p>
        )}
        {error && (
          <p style={{ color: "var(--state-error)" }}>Couldn&apos;t load the guide. Please try again in a moment.</p>
        )}
        {guide && (
          <>
            <p className="text-muted-foreground">{guide.intro}</p>

            <section className="space-y-2">
              <h3 className="font-semibold text-foreground">How it works</h3>
              <ol className="list-decimal space-y-1.5 pl-5 text-muted-foreground">
                {guide.steps.map((s) => (
                  <li key={s}>{s}</li>
                ))}
              </ol>
            </section>

            <section className="space-y-2">
              <h3 className="font-semibold text-foreground">The principles behind the checks</h3>
              <ul className="space-y-1.5 text-muted-foreground">
                {guide.principles.map((p) => (
                  <li key={p.name}>
                    <span className="font-medium text-foreground">{p.name}.</span> {p.why}
                  </li>
                ))}
              </ul>
            </section>

            <section className="space-y-2">
              <h3 className="font-semibold text-foreground">Every check</h3>
              <p className="text-muted-foreground">
                <strong>Must fix</strong> items stop the submission. <strong>Suggestion</strong> items are advice. Items
                marked <em>AI</em> are judged by the AI reviewer; the rest are automatic.
              </p>
              <ul className="space-y-2">
                {guide.rules.map((r) => (
                  <li key={r.code} className="rounded-lg border border-border/70 px-3 py-2">
                    <p className="flex flex-wrap items-center gap-2 text-foreground">
                      <span
                        className="rounded-full px-2 py-0.5 text-[0.625rem] font-semibold uppercase"
                        style={{
                          color: r.severity === "BLOCKER" ? "var(--state-error)" : "var(--state-queued)",
                          border: "1px solid color-mix(in srgb, currentColor 35%, transparent)",
                        }}
                      >
                        {r.severity === "BLOCKER" ? "Must fix" : "Suggestion"}
                      </span>
                      {r.checker === "AI" && (
                        <span className="rounded-full border border-border/70 px-2 py-0.5 text-[0.625rem] font-semibold text-muted-foreground">
                          AI
                        </span>
                      )}
                      <span className="font-medium">{r.requirement}</span>
                    </p>
                    <p className="mt-1 text-muted-foreground">
                      <span className="font-medium text-foreground">How to pass: </span>
                      {r.howToFix}
                    </p>
                  </li>
                ))}
              </ul>
            </section>

            <section className="space-y-2">
              <h3 className="font-semibold text-foreground">A worked example</h3>
              <p className="text-muted-foreground">Summary</p>
              <pre className="whitespace-pre-wrap rounded-lg bg-[var(--surface-sunken,transparent)] p-3 text-xs text-foreground">
                {guide.exampleSummary}
              </pre>
              <p className="text-muted-foreground">System prompt (adapt it - don&apos;t paste it unchanged)</p>
              <pre className="whitespace-pre-wrap rounded-lg bg-[var(--surface-sunken,transparent)] p-3 text-xs text-foreground">
                {guide.examplePrompt}
              </pre>
              <button
                type="button"
                onClick={() => copy(guide.examplePrompt)}
                className="inline-flex items-center gap-1.5 text-xs font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary rounded"
              >
                <Copy size={12} aria-hidden /> Copy example prompt
              </button>
            </section>
          </>
        )}
      </div>
    </details>
  );
}
