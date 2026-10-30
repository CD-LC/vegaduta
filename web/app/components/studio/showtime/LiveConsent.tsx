"use client";

import { ArrowRight } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";

// The one-time consent card in front of Showtime's live mode (split out of
// Showtime.tsx). Showtime keeps the checkbox state and remembers the pledge.

export default function LiveConsent({
  clientIp,
  agree,
  onAgree,
  onAccept,
}: {
  clientIp?: string | null;
  agree: boolean;
  onAgree: (agree: boolean) => void;
  onAccept: () => void;
}) {
    const tHC = useTranslations("hcComponentsStudioShowtimeLiveConsent");
  return (
    <div className="mx-auto mt-8 max-w-lg rounded-3xl border border-white/10 bg-zinc-900/80 p-6 shadow-2xl">
      <h3 className="text-xl font-semibold">{tHC("s6")}</h3>
      <p className="mt-2 text-sm text-zinc-400">
        {tHC("s5")}{" "}
        {clientIp ? (
          <>
            {tHC("s4")}<span className="font-mono">{clientIp}</span>{tHC("s3")}
          </>
        ) : (
          "Your network address is recorded against this sandbox."
        )}
      </p>
      <label className="mt-4 flex items-start gap-2 text-sm">
        <input type="checkbox" checked={agree} onChange={(e) => onAgree(e.target.checked)} className="mt-1 h-4 w-4" />
        {tHC("s2")}
      </label>
      <button
        type="button"
        disabled={!agree}
        onClick={onAccept}
        className="mt-4 inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-medium disabled:opacity-40"
      >
        {tHC("s1")} <ArrowRight aria-hidden="true" className="h-4 w-4 shrink-0" />
      </button>
    </div>
  );
}
