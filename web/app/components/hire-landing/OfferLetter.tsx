"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";
import type { BizKey } from "./bizData";
import { bizKey } from "./bizData";
import { sendHireEvent } from "./hireBeacon";
import BrandText from "../BrandText";

// The closing document — the offer letter with the two-signature ceremony.
// The agent's signature is pre-drawn; the visitor signs on a pointer-events
// canvas (mouse/touch/stylus, DPR-scaled). Drawing flips the primary CTA to
// "Send the signed offer" (mockup behaviour); clear resets it. Greeting and
// business name personalize from the interview.
export default function OfferLetter({ biz }: { biz: BizKey | null }) {
  const t = useTranslations("hire");
  const { variant } = useGeo();
  const isIndia = variant === "india";
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const ctxRef = useRef<CanvasRenderingContext2D | null>(null);
  const drawingRef = useRef(false);
  const beaconSentRef = useRef(false);
  const [signed, setSigned] = useState(false);

  // Size the canvas to its wrapper at device-pixel resolution; re-run on
  // resize (which also clears any strokes, same as the mockup).
  useEffect(() => {
    const canvas = canvasRef.current;
    const wrap = wrapRef.current;
    if (!canvas || !wrap) return undefined;
    const size = () => {
      const r = wrap.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      canvas.width = r.width * dpr;
      canvas.height = r.height * dpr;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.scale(dpr, dpr);
      ctx.lineWidth = 2;
      ctx.lineCap = "round";
      ctx.strokeStyle = getComputedStyle(canvas).getPropertyValue("--brand").trim() || "#4c43d8";
      ctxRef.current = ctx;
    };
    size();
    window.addEventListener("resize", size);
    return () => window.removeEventListener("resize", size);
  }, []);

  const pos = (e: React.PointerEvent<HTMLCanvasElement>): [number, number] => {
    const r = e.currentTarget.getBoundingClientRect();
    return [e.clientX - r.left, e.clientY - r.top];
  };

  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const ctx = ctxRef.current;
    if (!ctx) return;
    drawingRef.current = true;
    e.currentTarget.setPointerCapture(e.pointerId);
    const [x, y] = pos(e);
    ctx.beginPath();
    ctx.moveTo(x, y);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const ctx = ctxRef.current;
    if (!ctx || !drawingRef.current) return;
    const [x, y] = pos(e);
    ctx.lineTo(x, y);
    ctx.stroke();
    if (!signed) setSigned(true);
    if (!beaconSentRef.current) {
      beaconSentRef.current = true;
      sendHireEvent("hire_sign_draw");
    }
  };

  const onPointerUp = () => {
    drawingRef.current = false;
  };

  const clear = () => {
    const ctx = ctxRef.current;
    const canvas = canvasRef.current;
    if (ctx && canvas) ctx.clearRect(0, 0, canvas.width, canvas.height);
    setSigned(false);
  };

  const ctaClick = () => sendHireEvent("hire_cta_click");

  return (
    <section className="offer" id="offer">
      <div className="wrap">
        <p className="doc-eyebrow" style={{ textAlign: "center" }}>
          {t("offer_eyebrow")}
        </p>
        <h2 className="serif" style={{ textAlign: "center" }}>
          {t("offer_title")}
        </h2>
        <div className="doc">
          <span className="clip" aria-hidden="true"></span>
          <div className="doc-inner">
            <p className="date tw">{t("offer_date")}</p>
            <p>{t(bizKey("greet", biz))}</p>
            <p>
              {t("offer_p1_a")}
              <b>{t("offer_p1_b")}</b>
              {t("offer_p1_c")}
              <span>{t(bizKey("biz", biz))}</span>
              {t("offer_p1_d")}
            </p>
            <p>
              {t("offer_p2_a")}
              <b>{t(isIndia ? "offer_p2_bIN" : "offer_p2_b")}</b>
              {t("offer_p2_c")}
            </p>
            <div className="sig-row">
              <div className="sig-line">
                <div className="line">
                  <span className="signed"><BrandText text={t("offer_candidateSig")} /></span>
                </div>
                <small>{t("offer_candidateLabel")}</small>
              </div>
              <div className="sig-line">
                <div className="line" ref={wrapRef}>
                  <canvas
                    ref={canvasRef}
                    data-testid="hire-signature-canvas"
                    aria-label={t("offer_sigAria")}
                    onPointerDown={onPointerDown}
                    onPointerMove={onPointerMove}
                    onPointerUp={onPointerUp}
                    onPointerCancel={onPointerUp}
                  />
                </div>
                <small>{t("offer_ownerLabel")}</small>
                <button className="sig-clear" type="button" onClick={clear}>
                  {t("offer_sigClear")}
                </button>
              </div>
            </div>
            <div className="offer-ctas">
              <Link
                className="btn btn-primary"
                href="/signup"
                data-testid="hire-cta-offer"
                onClick={ctaClick}
              >
                {signed ? t("offer_ctaSigned") : t("offer_cta")}
              </Link>
              <Link
                className="btn btn-ghost"
                href="/try"
                data-testid="hire-cta-demo"
                onClick={ctaClick}
              >
                {t("offer_ctaDemo")}
              </Link>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
