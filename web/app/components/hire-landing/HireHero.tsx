"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";
import type { BizKey } from "./bizData";
import { BIZ_KEYS } from "./bizData";
import { sendHireEvent } from "./hireBeacon";
import { usePrefersReducedMotion } from "./usePrefersReducedMotion";

// Hero: headline + SITUATION WANTED classified (animated red circle + role
// rotator) + the interview room whose two questions personalize the whole
// dossier below (mockup's BIZ map — see bizData.ts). The intro bubble is in
// the server-rendered HTML so the section reads fine with JS disabled; the
// scripted Q&A only starts client-side. All copy via the hire namespace —
// messages carry a `kind` (not text) so the state machine survives locale
// switches mid-conversation.

// role2/role6 have an India-flavored variant (kirana/dukaans) — the rest are
// already country-neutral and shared by both.
function roleKeys(isIndia: boolean): string[] {
  return [
    "classified_role1",
    isIndia ? "classified_role2IN" : "classified_role2",
    "classified_role3",
    "classified_role4",
    "classified_role5",
    isIndia ? "classified_role6IN" : "classified_role6",
  ];
}

type AgentKind = "intro" | "q1" | "q2" | "final";

interface Msg {
  id: number;
  role: "agent" | "you";
  /** agent messages: which script line; resolved through t() at render time */
  kind?: AgentKind;
  /** q2 interpolation — the picked business label, resolved at click time */
  label?: string;
  /** "you" messages: the choice text as clicked */
  youText?: string;
  /** agent messages type out (9ms/char, mockup timing) unless reduced motion */
  animate: boolean;
}

function TypedText({
  text,
  animate,
  onDone,
}: {
  text: string;
  animate: boolean;
  onDone?: () => void;
}) {
  const [len, setLen] = useState(animate ? 0 : text.length);
  const onDoneRef = useRef(onDone);
  onDoneRef.current = onDone;
  useEffect(() => {
    if (!animate) {
      setLen(text.length);
      onDoneRef.current?.();
      return undefined;
    }
    let i = 0;
    let timer = 0;
    const tick = () => {
      i += 1;
      setLen(i);
      if (i < text.length) timer = window.setTimeout(tick, 9);
      else onDoneRef.current?.();
    };
    timer = window.setTimeout(tick, 9);
    return () => window.clearTimeout(timer);
  }, [text, animate]);
  return <>{text.slice(0, len)}</>;
}

export default function HireHero({ onBizSelected }: { onBizSelected: (k: BizKey) => void }) {
  const t = useTranslations("hire");
  const reduced = usePrefersReducedMotion();
  const { variant } = useGeo();
  const isIndia = variant === "india";
  const ROLE_KEYS = roleKeys(isIndia);

  /* ---- classified rotator ---- */
  const [roleIndex, setRoleIndex] = useState(0);
  const roleCount = ROLE_KEYS.length;
  useEffect(() => {
    if (reduced) return undefined;
    const id = window.setInterval(() => setRoleIndex((i) => (i + 1) % roleCount), 2600);
    return () => window.clearInterval(id);
  }, [reduced, roleCount]);

  /* ---- interview state machine ---- */
  const [messages, setMessages] = useState<Msg[]>([
    { id: 0, role: "agent", kind: "intro", animate: false },
  ]);
  const [choices, setChoices] = useState<"q1" | "q2" | null>(null);
  // Screen-reader echo of the interview log (a11y fix, 2026-09-28): the
  // visible log types out character by character via TypedText, which would
  // otherwise fire dozens of aria-live announcements per message. This
  // sr-only region is instead set ONCE per message, with its full text, only
  // after that message finishes appearing.
  const [liveAnnouncement, setLiveAnnouncement] = useState("");
  const nextId = useRef(1);
  const logRef = useRef<HTMLDivElement>(null);
  const startedRef = useRef(false);
  // reduced-motion value at the moment a message is pushed — stable per message.
  const reducedRef = useRef(reduced);
  reducedRef.current = reduced;

  const pushAgent = (kind: AgentKind, label?: string) => {
    const id = nextId.current++;
    setMessages((m) => [...m, { id, role: "agent", kind, label, animate: !reducedRef.current }]);
  };
  const pushYou = (youText: string) => {
    const id = nextId.current++;
    setMessages((m) => [...m, { id, role: "you", youText, animate: false }]);
    setLiveAnnouncement(youText);
  };

  // Kick off Q1 once, after the mockup's 600ms beat (100ms reduced).
  useEffect(() => {
    if (startedRef.current) return undefined;
    startedRef.current = true;
    const timer = window.setTimeout(() => pushAgent("q1"), reducedRef.current ? 100 : 600);
    return () => window.clearTimeout(timer);
     
  }, []);

  // Keep the newest bubble in view (block:nearest — no page-level jumps).
  useEffect(() => {
    if (messages.length <= 1) return;
    const last = logRef.current?.lastElementChild;
    last?.scrollIntoView({ block: "nearest", behavior: reducedRef.current ? "auto" : "smooth" });
  }, [messages]);

  // When an agent message finishes typing, the matching choice set appears.
  const onAgentDone = (kind: AgentKind | undefined) => {
    if (kind === "q1") setChoices("q1");
    else if (kind === "q2") setChoices("q2");
  };

  const pickQ1 = (k: BizKey) => {
    sendHireEvent("hire_interview_answer");
    setChoices(null);
    pushYou(t("iv_choice_" + k));
    window.setTimeout(
      () => {
        onBizSelected(k);
        pushAgent("q2", t("biz_" + k + "_label"));
      },
      reducedRef.current ? 0 : 250
    );
  };

  const pickQ2 = (label: string) => {
    sendHireEvent("hire_interview_answer");
    setChoices(null);
    pushYou(label);
    window.setTimeout(() => pushAgent("final"), reducedRef.current ? 0 : 250);
  };

  const agentText = (m: Msg): string => {
    if (m.kind === "intro") return t("iv_intro");
    if (m.kind === "q1") return t("iv_q1");
    if (m.kind === "q2") return t("iv_q2", { label: m.label ?? "" });
    return t(isIndia ? "iv_finalIN" : "iv_final");
  };

  return (
    <header className="hero">
      <div className="wrap">
        <div className="hero-head">
          <p className="doc-eyebrow">{t("hero_eyebrow")}</p>
          <h1 className="serif">
            {t("hero_title_a")}
            <br />
            <em>{t("hero_title_em")}</em>
          </h1>
          <p className="lede">{t(isIndia ? "hero_ledeIN" : "hero_lede")}</p>
        </div>
        <div className="hero-grid">
          <div className="doc classified tilt-l">
            <span className="clip" aria-hidden="true"></span>
            <div className="doc-inner">
              <h3>{t("classified_heading")}</h3>
              <p>
                <b className="circled">
                  {t("classified_circled")}
                  <svg viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
                    <ellipse cx="50" cy="20" rx="49" ry="18" />
                  </svg>
                </b>{" "}
                {t("classified_at")} <span className="rotator">{t(ROLE_KEYS[roleIndex])}</span>.
              </p>
              <p>{t(isIndia ? "classified_p2IN" : "classified_p2")}</p>
              <p>{t("classified_p3")}</p>
              <p className="fine">{t(isIndia ? "classified_fineIN" : "classified_fine")}</p>
            </div>
          </div>
          <div className="doc interview tilt-r">
            <div className="doc-inner">
              <div className="iv-title">
                <span>{t("iv_title")}</span>
                <span className="tw">{t("iv_recording")}</span>
              </div>
              <div className="iv-log" ref={logRef}>
                {messages.map((m) => (
                  <div
                    key={m.id}
                    className={"bubble " + (m.role === "you" ? "you" : "agent")}
                    aria-hidden={m.role === "agent" ? "true" : undefined}
                  >
                    {m.role === "agent" ? (
                      <TypedText
                        text={agentText(m)}
                        animate={m.animate}
                        onDone={() => {
                          onAgentDone(m.kind);
                          setLiveAnnouncement(agentText(m));
                        }}
                      />
                    ) : (
                      m.youText
                    )}
                  </div>
                ))}
              </div>
              <span className="sr-only" aria-live="polite">{liveAnnouncement}</span>
              <div className="iv-choices" aria-label={t("iv_choicesAria")}>
                {choices === "q1" &&
                  BIZ_KEYS.map((k) => (
                    <button
                      key={k}
                      type="button"
                      data-testid={"hire-interview-choice-" + k}
                      onClick={() => pickQ1(k)}
                    >
                      {t("iv_choice_" + k)}
                    </button>
                  ))}
                {choices === "q2" &&
                  [1, 2, 3].map((n) => (
                    <button
                      key={n}
                      type="button"
                      data-testid={"hire-interview-choice-q2-" + n}
                      onClick={() => pickQ2(t("iv_q2_choice" + n))}
                    >
                      {t("iv_q2_choice" + n)}
                    </button>
                  ))}
              </div>
              <p className="iv-hint">{t("iv_hint")}</p>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}
