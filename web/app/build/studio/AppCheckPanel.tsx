"use client";

import { useMemo } from "react";
import { AlertTriangle, Check, Sparkles, XCircle } from "lucide-react";
import { Button } from "../../components/ui/button";
import { appCheck, fixRequestFromFindings } from "./appCheck";

/** Static lint of the current app (viewport, alt text, tap targets, contrast,
 * language). "Fix with AI" hands the findings to the normal AI edit path. */
export function AppCheckPanel({
  html,
  busy,
  disabled,
  onFix,
}: {
  html: string;
  busy: boolean;
  disabled?: boolean;
  onFix: (request: string, count: number) => void;
}) {
  const findings = useMemo(() => appCheck(html), [html]);
  const errors = findings.filter((f) => f.severity === "error").length;
  return (
    <div className="flex flex-col gap-3">
      <div>
        <p className="text-sm font-semibold text-foreground">App check</p>
        <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
          A quick scan for what makes a phone app hard to use. It reads the app&apos;s source only, so treat it as a checklist, not a verdict.
        </p>
      </div>
      {findings.length === 0 ? (
        <p className="flex items-center gap-2 rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3 text-xs text-emerald-700 dark:text-emerald-300">
          <Check aria-hidden="true" size={14} /> Nothing found: viewport, language, image text, tap sizes and colours all look fine.
        </p>
      ) : (
        <>
          <p className="text-xs text-muted-foreground" role="status">
            {findings.length} thing{findings.length === 1 ? "" : "s"} to look at{errors ? `, ${errors} important` : ""}.
          </p>
          <ul className="flex flex-col gap-2">
            {findings.map((f) => (
              <li key={f.id} className="flex gap-2 rounded-xl border border-border p-2.5">
                {f.severity === "error" ? (
                  <XCircle size={15} className="mt-0.5 shrink-0 text-destructive" aria-label="Important" />
                ) : (
                  <AlertTriangle size={15} className="mt-0.5 shrink-0 text-amber-500" aria-label="Worth fixing" />
                )}
                <span className="min-w-0">
                  <span className="block text-xs font-medium text-foreground">{f.title}</span>
                  <span className="block break-words text-[11px] leading-relaxed text-muted-foreground">{f.detail}</span>
                </span>
              </li>
            ))}
          </ul>
          <Button size="sm" className="gap-1.5" disabled={busy || disabled} onClick={() => onFix(fixRequestFromFindings(findings), findings.length)}>
            <Sparkles aria-hidden="true" size={13} /> Fix with AI
          </Button>
        </>
      )}
    </div>
  );
}
