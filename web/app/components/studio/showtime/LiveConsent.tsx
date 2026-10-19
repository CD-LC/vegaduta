"use client";

import { ArrowRight } from "lucide-react";

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
  return (
    <div className="mx-auto mt-8 max-w-lg rounded-3xl border border-white/10 bg-zinc-900/80 p-6 shadow-2xl">
      <h3 className="text-xl font-semibold">Live mode runs real agents.</h3>
      <p className="mt-2 text-sm text-zinc-400">
        No signup, no card. In return: no illegal or abusive use, nothing private pasted in, and fair-use limits apply.{" "}
        {clientIp ? (
          <>
            Your address (<span className="font-mono">{clientIp}</span>) is recorded against this sandbox.
          </>
        ) : (
          "Your network address is recorded against this sandbox."
        )}
      </p>
      <label className="mt-4 flex items-start gap-2 text-sm">
        <input type="checkbox" checked={agree} onChange={(e) => onAgree(e.target.checked)} className="mt-1 h-4 w-4" />
        I&apos;ll use the studio for legitimate work only, and I understand my address is recorded.
      </label>
      <button
        type="button"
        disabled={!agree}
        onClick={onAccept}
        className="mt-4 inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-medium disabled:opacity-40"
      >
        I agree - start building <ArrowRight aria-hidden="true" className="h-4 w-4 shrink-0" />
      </button>
    </div>
  );
}
