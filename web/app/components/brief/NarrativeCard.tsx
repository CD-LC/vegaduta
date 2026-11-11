"use client";

import { useRef } from "react";
import Link from "next/link";
import { ExternalLink, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { editorTargetFor, labelOfRef } from "./logic";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import type { Brief, BriefDetail } from "./types";

// Right drawer for one node: why it exists, what to tune if results are wrong,
// how the user will know it worked. The three paragraphs are planner output in
// the user's locale and are rendered exactly as written, never through t().
// A node with no entry says so instead of inventing one.
export default function NarrativeCard({
  brief,
  refKey,
  detail,
  onClose,
}: {
  brief: Brief;
  refKey: string;
  /** Present once the envoy is applied; carries the bindings the editor link needs. */
  detail?: Pick<BriefDetail, "bindings"> | null;
  onClose: () => void;
}) {
  const t = useTranslations("brief");
  const shown = brief.narrative.perNode[refKey];
  const target = editorTargetFor(refKey, detail ?? null);
  const closeRef = useRef<HTMLButtonElement>(null);

  const panelRef = useRef<HTMLElement>(null);
  useDialogFocus(panelRef, closeRef, onClose);

  const sections: { key: "why" | "tune" | "proof"; label: string }[] = [
    { key: "why", label: t("narrative.why") },
    { key: "tune", label: t("narrative.tune") },
    { key: "proof", label: t("narrative.proof") },
  ];

  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/40" onClick={onClose} aria-hidden="true" />
      <aside
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={t("narrative.aria", { name: labelOfRef(brief, refKey) })}
        className="elev-raised fixed inset-y-0 end-0 z-40 flex w-[min(26rem,100vw)] flex-col gap-4 overflow-y-auto border-s border-border p-5 shadow-xl"
      >
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-base font-semibold leading-snug text-foreground">{labelOfRef(brief, refKey)}</h3>
          <Button ref={closeRef} variant="ghost" size="icon" onClick={onClose} aria-label={t("narrative.close")}>
            <X className="size-4" aria-hidden="true" />
          </Button>
        </div>

        {shown ? (
          <dl className="space-y-4">
            {sections.map((s) => (
              <div key={s.key} className="space-y-1">
                <dt className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{s.label}</dt>
                <dd className="text-sm leading-relaxed text-foreground">{shown[s.key]}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <p className="text-sm text-muted-foreground">{t("narrative.none")}</p>
        )}

        <div className="mt-auto space-y-1.5 border-t border-border pt-4">
          {target ? (
            <Button asChild variant="outline" className="w-full">
              <Link href={target.href}>
                <ExternalLink className="size-4" aria-hidden="true" />
                {t("narrative.openEditor")}
              </Link>
            </Button>
          ) : (
            <>
              <Button variant="outline" className="w-full" disabled>
                <ExternalLink className="size-4" aria-hidden="true" />
                {t("narrative.openEditor")}
              </Button>
              <p className="text-xs text-muted-foreground">{t("narrative.openEditorLater")}</p>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
