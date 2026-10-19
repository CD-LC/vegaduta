"use client";

import { useState } from "react";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";
import type { BizKey } from "./bizData";
import { bizKey } from "./bizData";
import { sendHireEvent } from "./hireBeacon";

// Document 2 of 5 — the duty register work trial, carrying THE signature
// interaction (research digest: "THE STAMP gets the polish budget"): a
// tap-to-stamp approval with the mockup's slam/recoil animation (pure CSS,
// reduced-motion aware — see hire-landing.css). The first register entry and
// the approval message personalize from the interview. All copy via the hire
// namespace; the register note links to the real no-signup demo (/try).
export default function WorkTrial({ biz }: { biz: BizKey | null }) {
  const t = useTranslations("hire");
  const { variant } = useGeo();
  const isIndia = variant === "india";
  const [stamped, setStamped] = useState(false);

  const stamp = () => {
    sendHireEvent("hire_stamp_click");
    setStamped(true);
  };

  return (
    <section id="trial">
      <div className="wrap">
        <p className="doc-eyebrow">{t("trial_eyebrow")}</p>
        <h2 className="serif">{t("trial_title")}</h2>
        <p className="lede">
          {t("trial_lede_a")}
          {biz ? t("trial_ledeAt", { label: t(bizKey("label", biz)) }) : ""}
          {t("trial_lede_b")}
        </p>
        <div className="doc register" style={{ marginTop: 34 }}>
          <span className="clip" aria-hidden="true"></span>
          <div className="doc-inner">
            <div className="letterhead">
              <b>{t("trial_letterhead")}</b>
              <span>{t("trial_letterheadSub")}</span>
            </div>
            <div className="reg-row">
              <time>08:02</time>
              <div className="what">
                <b>{t(bizKey("t1h", biz))}</b>
                <p>{t(bizKey(isIndia ? "t1pIN" : "t1p", biz))}</p>
              </div>
              <span className="reg-tag">{t("trial_tag_whatsapp")}</span>
            </div>
            <div className="reg-row">
              <time>10:40</time>
              <div className="what">
                <b>{t("trial_r2h")}</b>
                <p>{t("trial_r2p")}</p>
              </div>
              <span className="reg-tag">{t("trial_tag_voice")}</span>
            </div>
            <div className="reg-row">
              <time>12:15</time>
              <div className="what">
                <b>{t("trial_r3h")}</b>
                <p>{t("trial_r3p")}</p>
              </div>
              <span className="reg-tag">{t("trial_tag_workflow")}</span>
            </div>
            <div className="reg-row">
              <time>14:00</time>
              <div className="what">
                <b>{t("trial_r4h")}</b>
                <p>{t("trial_r4p")}</p>
                <div className={"approval-card" + (stamped ? " done" : "")}>
                  <div className="from">{t("approve_from")}</div>
                  <div className="msg">{t(bizKey(isIndia ? "approveIN" : "approve", biz))}</div>
                  <div className="stamp-tray">
                    <button
                      className="stampbtn"
                      type="button"
                      data-testid="hire-stamp-button"
                      onClick={stamp}
                      aria-label={t("approve_stampAria")}
                    >
                      <span className="handle"></span>
                      <span className="base"></span>
                      <small>{t("approve_stampLabel")}</small>
                    </button>
                    <span style={{ fontSize: "12.5px", color: "var(--ink2)" }}>
                      {t("approve_orEdit")}
                    </span>
                  </div>
                  <span className="stamp inkmark">{t("approve_inkmark")}</span>
                  <p className="boss-line">
                    {t("approve_boss_a")}
                    <b>{t(isIndia ? "approve_boss_bIN" : "approve_boss_b")}</b>
                  </p>
                </div>
              </div>
              <span className="reg-tag hold">{t("trial_tag_approval")}</span>
            </div>
            <div className="reg-row">
              <time>18:00</time>
              <div className="what">
                <b>{t("trial_r5h")}</b>
                <p>{t(isIndia ? "trial_r5pIN" : "trial_r5p")}</p>
              </div>
              <span className="reg-tag">{t("trial_tag_budget")}</span>
            </div>
            <div className="reg-row">
              <time>22:47</time>
              <div className="what">
                <b>{t("trial_r6h")}</b>
                <p>{t("trial_r6p")}</p>
              </div>
              <span className="reg-tag">{t("trial_tag_always")}</span>
            </div>
            <p className="reg-note">
              {t("trial_note_a")}
              <Link href="/try">{t("trial_note_link")}</Link>
              {t("trial_note_b")}
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
