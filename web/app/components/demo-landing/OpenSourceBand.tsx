"use client";

import Link from "next/link";
import {
  ArrowRight,
  Coffee,
  Folder,
  FolderGit2,
  Github,
  Star,
} from "lucide-react";
import { COFFEE_URL, HAS_COFFEE_LINK } from "../../lib/freeMode";
import { useTranslations } from "../../lib/i18n";
import { sendLandingEvent } from "./landingBeacon";
import BrandText from "../BrandText";

// ---------------------------------------------------------------------------
// OpenSourceBand (2026-09-22, direct instruction: "instead of pricing put it
// as open source"). The pricing slot now answers "why pricing?" with the
// actual answer — there is none, because the platform is going open source.
// The band is a GitHub-style repo card announcing that the code is being
// organised for a PUBLIC repository, and that this space will link straight
// to it once the repo opens. No repo URL is rendered anywhere on purpose:
// it does not exist publicly yet, and a dead github.com link is a worse
// promise than none.
//
// PricingBand.tsx stays in the repo unmounted (the "kept for reference /
// rollback" convention every other cut band follows); the mount site in
// DemoLanding still reports SectionReach "pricing" so the reach waterfall
// keeps one series across the swap — the same trick CapabilityAtlas used
// when it took PlatformLedger's "ledger" slot.
//
// Scroll entrance is the CSS-only `.mkt-reveal` like its neighbours: nothing
// here can be left at opacity 0 if JS never runs.
// ---------------------------------------------------------------------------

const REPO_ROWS: ReadonlyArray<{ dir: string; commitKey: string }> = [
  { dir: "core/", commitKey: "oss_rowCore" },
  { dir: "web/", commitKey: "oss_rowWeb" },
  { dir: "sidecar/", commitKey: "oss_rowSidecar" },
  { dir: "clients/", commitKey: "oss_rowClients" },
  { dir: "docs/", commitKey: "oss_rowDocs" },
];

export default function OpenSourceBand() {
  const t = useTranslations("landing");

  return (
    <section id="open-source" className="mkt-band mkt-band--ruled">
      <div className="mkt-band__inner mkt-spot">
        <div className="mkt-head mkt-reveal">
          <p className="mkt-eyebrow">{t("oss_eyebrow")}</p>
          <h2 className="mkt-h2">{t("oss_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2"><BrandText text={t("oss_sub")} /></p>
        </div>

        {/* The repo card — a GitHub-flavoured preview of what opens here.
            The file rows carry "commit messages" that tell the truth: the
            code is being organised (secrets scrubbed, docs on record) before
            the repo flips public. */}
        <div className="mkt-reveal mt-8">
          <div className="mkt-card mkt-card--raised mx-auto max-w-2xl overflow-hidden p-0 text-left">
            <div className="flex items-center gap-2.5 border-b border-border/60 px-5 py-3.5">
              <Github className="size-4 shrink-0" aria-hidden="true" />
              <span className="mkt-small font-medium">
                vegaduta<span className="mkt-ink-2"> / </span>vegaduta
              </span>
              <span className="mkt-pill mkt-pill--accent mkt-badge ml-auto shrink-0">
                {t("oss_badgeSoon")}
              </span>
            </div>
            <ul className="divide-y divide-border/40 px-5 py-1 font-mono text-xs">
              {REPO_ROWS.map((row) => (
                <li key={row.dir} className="flex items-center gap-2.5 py-2">
                  <Folder
                    className="size-3.5 shrink-0 text-muted-foreground"
                    aria-hidden="true"
                  />
                  <span className="shrink-0 text-foreground">{row.dir}</span>
                  <span className="mkt-ink-2 truncate font-sans">
                    {t(row.commitKey)}
                  </span>
                </li>
              ))}
            </ul>
            <div className="border-t border-border/60 bg-muted/30 px-5 py-3 font-mono text-xs">
              <p className="text-foreground/80">
                <span className="mkt-ink-2">$</span> git clone
                https://github.com/CD-LC/vegaduta.git
              </p>
              <p className="mkt-ink-2 mt-1">{t("oss_cloneNote")}</p>
            </div>
          </div>
        </div>

        {/* CTAs. The /try button is the one live action — the Star button is
            deliberately aria-disabled, not a dead link: the repo URL doesn't
            exist publicly yet, so there's nothing honest to point it at. */}
        <div className="mkt-reveal mt-6 flex flex-wrap items-center justify-center gap-2">
          <Link
            href="/try"
            onClick={() => sendLandingEvent("oss_try_click")}
            className="mkt-btn mkt-btn-primary"
          >
            {t("oss_ctaTry")}
            <ArrowRight className="size-3.5" aria-hidden="true" />
          </Link>
          <span
            aria-disabled="true"
            className="mkt-btn mkt-btn-secondary cursor-not-allowed opacity-70"
          >
            <Star className="size-3.5" aria-hidden="true" />
            {t("oss_ctaStar")}
            <span className="mkt-badge rounded-full bg-primary/10 px-2 py-0.5 text-primary">
              {t("oss_soonTag")}
            </span>
          </span>
          {HAS_COFFEE_LINK && (
            <a
              href={COFFEE_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="mkt-btn mkt-btn-secondary"
            >
              <Coffee className="size-3.5" aria-hidden="true" />
              {t("pricing_free_coffee")}
            </a>
          )}
        </div>
        <p className="mkt-caption mkt-ink-2 mkt-reveal mt-5 text-center">
          <FolderGit2 className="mr-1.5 inline size-3.5 align-[-2px]" aria-hidden="true" />
          {t("oss_foot")}
        </p>
      </div>
    </section>
  );
}
