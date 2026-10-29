"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { academyHref } from "../../lib/academyUrl";
import { useAuthedHint } from "../../lib/hooks/useAuthedHint";
import "./hire-landing.css";

import type { BizKey } from "./bizData";
import { sendHireEvent, sendHireViewOnce } from "./hireBeacon";
import HireHero from "./HireHero";
import PledgeBand from "./PledgeBand";
import AgentCv from "./AgentCv";
import WorkTrial from "./WorkTrial";
import CandidatePool from "./CandidatePool";
import SalaryWorksheet from "./SalaryWorksheet";
import EmploymentContract from "./EmploymentContract";
import HireFaq from "./HireFaq";
import OfferLetter from "./OfferLetter";
import ThemePicker from "../ThemePicker";
import SensesStrip from "../marketing/SensesStrip";
import { isBillingEnabled } from "../../lib/billing-flag";
import BrandText from "../BrandText";

// /hire — the "Hiring Papers" landing (2026-07-31 redesign, approved v3
// mockup: scratchpad/vegaduta-landing-v1.html). The page is the employment
// dossier of your first AI hire: classified ad → interview (which rewrites
// the dossier) → pledge → CV → work trial (tap-to-stamp approval) → candidate
// pool → salary worksheet + payslips → contract → FAQ → offer letter with a
// signature canvas.
//
// PUBLIC route (lib/auth.ts PUBLIC_ROUTES) — renders with NO AuthContext, so
// nothing in this tree may import AppShell or useAuth (the /docs standalone-
// chrome pattern; an AppShell here is an SSR 500). Copy goes through
// useTranslations("hire") (landing-i18n precedent, e94f580b); styling is the
// mockup's own stylesheet scoped under .hire-scope (see hire-landing.css) —
// deliberately NOT the console's Tailwind/shadCN tokens, because the paper
// grammar (desk background, tilted docs, stamps) is the design.
//
// One deviation from the mockup, per standing brand rule ("winged emblem
// ALWAYS, never the V monogram"): the nav logo is /logo-mark.png + wordmark
// instead of the mockup's V logomark.
export default function HireLanding() {
    const tHC = useTranslations("hcComponentsHirelandingHireLanding");
  const t = useTranslations("hire");
  const authedHint = useAuthedHint();
  const [biz, setBiz] = useState<BizKey | null>(null);

  useEffect(() => {
    sendHireViewOnce();
  }, []);

  const navCta = () => sendHireEvent("hire_cta_click");

  return (
    <>
      {/* Theme picker — allows visitors to toggle light/dark mode on public pages. */}
      <div className="fixed right-4 top-4 z-50">
        <ThemePicker />
      </div>

      <div className="vd-pub hire-scope">
        <nav>
        <div className="wrap navrow">
          <Link href="/" className="logo" aria-label={t("nav_homeAria")}>
            <Image
              src="/logo-mark.png"
              alt=""
              height={26}
              width={36}
              style={{ height: 26, width: "auto" }}
              draggable={false}
              priority
            />
            <span className="brand-name text-lg font-semibold tracking-tight">
              Vega<span className="text-primary">Dūta</span>
            </span>
          </Link>
          <div className="navlinks">
            <a href="#cv">{t("nav_cv")}</a>
            <a href="#trial">{t("nav_trial")}</a>
            <a href="#pool">{t("nav_pool")}</a>
            <a href="#salary">{t("nav_salary")}</a>
            <a href="#contract">{t("nav_contract")}</a>
            <a href="#faq">{t("nav_faq")}</a>
          </div>
          {/* vd_authed hint: a signed-in visitor gets "Open console", never a
              sign-in link to a session they already hold. */}
          {authedHint ? (
            <Link className="btn btn-ghost" href="/" style={{ padding: "7px 16px", fontSize: 14 }}>
              {tHC("hcComponentsHirelandingHireLanding.s1")}
            </Link>
          ) : (
            <Link className="btn btn-ghost" href="/login" style={{ padding: "7px 16px", fontSize: 14 }}>
              {t("nav_signIn")}
            </Link>
          )}
          <a
            className="btn btn-primary"
            href="#offer"
            data-testid="hire-cta-nav"
            onClick={navCta}
            style={{ padding: "7px 16px", fontSize: 14 }}
          >
            {t("nav_hireFree")}
          </a>
        </div>
      </nav>

      <HireHero onBizSelected={setBiz} />
      <PledgeBand />
      {/* The candidate's senses, compact — eyes/ears/voice/hands/wings live,
          IoT honestly "in design"; links to the landing's full band. Uses
          theme tokens, so it sits fine inside this page's dossier styling. */}
      <SensesStrip />
      <AgentCv biz={biz} />
      <WorkTrial biz={biz} />
      <CandidatePool />
      {/* Priced plan slips (Free/Premium/Unlimited) - billing-only surface. */}
      {isBillingEnabled() && <SalaryWorksheet biz={biz} />}
      <EmploymentContract />
      <HireFaq />
      <OfferLetter biz={biz} />

      <div className="wrap">
        <footer>
          <span><BrandText text={t("footer_tagline")} /></span>
          <span>
            <a href={academyHref()} target="_blank" rel="noopener noreferrer">
              {t("footer_academy")}
            </a>
            {" · "}
            <Link href="/docs">{t("footer_docs")}</Link>
            {" · "}
            <Link href="/templates">{t("footer_templates")}</Link>
            {" · "}
            {isBillingEnabled() && (
              <>
                <Link href="/billing">{t("footer_pricing")}</Link>
                {" · "}
              </>
            )}
            {t("footer_status")}
          </span>
        </footer>
      </div>
      </div>
    </>
  );
}
