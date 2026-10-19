"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";
import type { BizKey } from "./bizData";
import { BIZ_HRS, DEFAULT_HRS } from "./bizData";
import { sendHireEvent } from "./hireBeacon";

// Slip rows/labels that carry an India-specific (₹) variant — everything
// else in the three pricing slips is currency-neutral already and shared by
// both geos, so only these need the IN-suffixed lookup.
const VARIANT_SLIP_KEYS = new Set(["slip1_r1v", "slip2_r1v", "slip2_r6l", "slip2_r6v", "slip3_r1v"]);

// Document 4 of 5 — the salary worksheet (two sliders, visitor's own inputs,
// arithmetic only — "like a charges page, not a pitch") plus the three salary
// slips. Plan facts verified against the digest: Free ₹0 forever (5 agents,
// hosted 30 msgs/day, Telegram+Email, 1 KB collection), Premium ₹399/$19
// (25 agents · 10 seats, WhatsApp Twilio/Meta, BYOK 14 providers, 5 KB
// collections), Unlimited ₹1,599/$79 (no caps, 6 evaluators, 4h SLA support).
// The interview answer resets the hours slider to the picked business's
// typical load (mockup behaviour).
export default function SalaryWorksheet({ biz }: { biz: BizKey | null }) {
  const t = useTranslations("hire");
  const { variant } = useGeo();
  const isIndia = variant === "india";
  const [hrs, setHrs] = useState(DEFAULT_HRS);
  // ₹150/hr and $15/hr are the same rough order of magnitude for their
  // respective currencies — just a sane starting point for the slider.
  const [rate, setRate] = useState(() => (isIndia ? 150 : 15));
  const interactedRef = useRef(false);
  const keyFor = (base: string) => (isIndia && VARIANT_SLIP_KEYS.has(base) ? `${base}IN` : base);

  useEffect(() => {
    if (biz) setHrs(BIZ_HRS[biz]);
  }, [biz]);

  // hire_calc_interact once per page view — a per-drag event stream would
  // just multiply-count one visitor's curiosity.
  const markInteract = () => {
    if (interactedRef.current) return;
    interactedRef.current = true;
    sendHireEvent("hire_calc_interact");
  };

  const monthlyHours = hrs * 4.33;
  const fmtCurrency = (n: number) =>
    isIndia
      ? "₹" + Math.round(n).toLocaleString("en-IN")
      : "$" + Math.round(n).toLocaleString("en-US");

  const ctaClick = () => sendHireEvent("hire_cta_click");

  const slipRows = (slip: "slip1" | "slip2" | "slip3") =>
    [1, 2, 3, 4, 5, 6].map((n) => (
      <tr key={n}>
        <td>{t(keyFor(`${slip}_r${n}l`))}</td>
        <td>{t(keyFor(`${slip}_r${n}v`))}</td>
      </tr>
    ));

  return (
    <section id="salary">
      <div className="wrap">
        <p className="doc-eyebrow">{t("salary_eyebrow")}</p>
        <h2 className="serif">{t("salary_title")}</h2>
        <p className="lede">{t("salary_lede")}</p>

        <div className="calc">
          <div className="letterhead">
            <b>{t("calc_letterhead")}</b>
            <span className="tw">{t("calc_letterheadSub")}</span>
          </div>
          <div className="calc-grid">
            <div>
              <div className="row">
                <label htmlFor="hire-hrs">
                  {t("calc_hrsLabel_pre")}
                  <output id="hire-hrs-out">{hrs}</output>
                  {t("calc_hrsLabel_unit")}
                </label>
                <input
                  type="range"
                  id="hire-hrs"
                  data-testid="hire-calc-hours"
                  min={2}
                  max={40}
                  step={1}
                  value={hrs}
                  onChange={(e) => {
                    markInteract();
                    setHrs(Number(e.target.value));
                  }}
                />
              </div>
              <div className="row">
                <label htmlFor="hire-rate">
                  {t(isIndia ? "calc_rateLabel_preIN" : "calc_rateLabel_pre")}
                  <output id="hire-rate-out">{rate}</output>
                </label>
                <input
                  type="range"
                  id="hire-rate"
                  data-testid="hire-calc-rate"
                  min={isIndia ? 50 : 5}
                  max={isIndia ? 1000 : 100}
                  step={isIndia ? 25 : 5}
                  value={rate}
                  onChange={(e) => {
                    markInteract();
                    setRate(Number(e.target.value));
                  }}
                />
              </div>
            </div>
            <div className="calc-result" aria-live="polite">
              <div>
                <span>{t("calc_row1")}</span>
                <span>{t("calc_hoursValue", { hours: Math.round(monthlyHours) })}</span>
              </div>
              <div>
                <span>{t("calc_row2")}</span>
                <span>{t("calc_costValue", { cost: fmtCurrency(monthlyHours * rate) })}</span>
              </div>
              <div>
                <span>{t("calc_row3")}</span>
                <span>{t(isIndia ? "calc_row3ValueIN" : "calc_row3Value")}</span>
              </div>
              <div className="big">
                <span>{t("calc_rowBig")}</span>
                <span>{t("calc_saveValue", { hours: Math.round(monthlyHours) })}</span>
              </div>
              <p className="calc-fine">{t("calc_fine")}</p>
            </div>
          </div>
        </div>

        <div className="slips">
          <div className="slip">
            <h3>{t("slip1_h")}</h3>
            <div className="who">{t("slip1_who")}</div>
            <table>
              <tbody>
                {slipRows("slip1")}
                <tr className="total">
                  <td>{t("slip1_totalLabel")}</td>
                  <td>{t(isIndia ? "slip1_totalValueIN" : "slip1_totalValue")}</td>
                </tr>
              </tbody>
            </table>
            <div className="cta-row">
              <Link
                className="btn btn-ghost"
                href="/signup"
                data-testid="hire-cta-plan-free"
                onClick={ctaClick}
              >
                {t("slip1_cta")}
              </Link>
            </div>
          </div>
          <div className="slip hot">
            <span className="hot-badge" aria-hidden="true">
              {t("slips_hotBadge")}
            </span>
            <h3>{t("slip2_h")}</h3>
            <div className="who">{t("slip2_who")}</div>
            <table>
              <tbody>
                {slipRows("slip2")}
                <tr className="total">
                  <td>{t("slip2_totalLabel")}</td>
                  <td>{t(isIndia ? "slip2_totalValueIN" : "slip2_totalValue")}</td>
                </tr>
              </tbody>
            </table>
            <div className="cta-row">
              <Link
                className="btn btn-primary"
                href="/signup"
                data-testid="hire-cta-plan-premium"
                onClick={ctaClick}
              >
                {t("slip2_cta")}
              </Link>
            </div>
          </div>
          <div className="slip">
            <h3>{t("slip3_h")}</h3>
            <div className="who">{t("slip3_who")}</div>
            <table>
              <tbody>
                {slipRows("slip3")}
                <tr className="total">
                  <td>{t("slip3_totalLabel")}</td>
                  <td>{t(isIndia ? "slip3_totalValueIN" : "slip3_totalValue")}</td>
                </tr>
              </tbody>
            </table>
            <div className="cta-row">
              <Link
                className="btn btn-ghost"
                href="/signup"
                data-testid="hire-cta-plan-unlimited"
                onClick={ctaClick}
              >
                {t("slip3_cta")}
              </Link>
            </div>
          </div>
        </div>
        <p className="slip-note">
          {t("slips_note_a")}
          <Link href="/billing">{t("slips_note_link")}</Link>
          {t("slips_note_b")}
        </p>
      </div>
    </section>
  );
}
