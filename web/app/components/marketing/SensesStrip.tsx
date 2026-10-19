"use client";

import Link from "next/link";
import { Eye, Ear, Volume2, Hand, Bird, Bluetooth } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

// SensesStrip (2026-08-12) — the compact one-row echo of the landing's
// SensesShowcase, for the other public pages (/hire, /earn). Five live
// senses as chips + the honest dashed "IoT: in design" chip, linking back
// to the full band at /#senses. Same `senses` namespace, same claim ledger
// as SensesShowcase.tsx — this strip must never claim more than that band.
const CHIPS = [
  { icon: Eye, nameKey: "eyes_name" },
  { icon: Ear, nameKey: "ears_name" },
  { icon: Volume2, nameKey: "voice_name" },
  { icon: Hand, nameKey: "hands_name" },
  { icon: Bird, nameKey: "wings_name" },
];

export default function SensesStrip() {
  const t = useTranslations("senses");
  return (
    <div className="border-y border-border/50 bg-secondary/[0.12] px-5 py-5 sm:px-8">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-center gap-x-3 gap-y-2">
        <span className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
          {t("strip_caption")}
        </span>
        {CHIPS.map(({ icon: Icon, nameKey }) => (
          <span
            key={nameKey}
            className="inline-flex items-center gap-1.5 rounded-full border border-primary/30 bg-primary/10 px-3 py-1 text-xs font-medium text-primary"
          >
            <Icon className="size-3.5" aria-hidden="true" />
            {t(nameKey)}
          </span>
        ))}
        <span className="inline-flex items-center gap-1.5 rounded-full border border-dashed border-amber-500/50 bg-amber-500/10 px-3 py-1 text-xs font-medium text-amber-700 dark:text-amber-400">
          <Bluetooth className="size-3.5" aria-hidden="true" />
          {t("strip_iot")}
        </span>
        <Link href="/#senses" className="text-xs font-semibold text-primary underline-offset-2 hover:underline">
          {t("strip_link")} →
        </Link>
      </div>
    </div>
  );
}
