"use client";

import { useTranslations } from "../../lib/i18n";

// Document 3 of 5 — the candidate pool. Numbers verified against the fact
// base: 29 built-in agent templates, 248 recipes across 89 industry packs
// (counted from the live recipe index), community gallery human-reviewed.
export default function CandidatePool() {
  const t = useTranslations("hire");
  const cards: { key: string; tilt: string }[] = [
    { key: "pool_c1", tilt: " tilt-l" },
    { key: "pool_c2", tilt: "" },
    { key: "pool_c3", tilt: " tilt-r" },
  ];
  return (
    <section id="pool">
      <div className="wrap">
        <p className="doc-eyebrow">{t("pool_eyebrow")}</p>
        <h2 className="serif">{t("pool_title")}</h2>
        <p className="lede">{t("pool_lede")}</p>
        <div className="pool">
          {cards.map((c) => (
            <div key={c.key} className={"cand" + c.tilt}>
              <span className="stamp">{t("pool_reviewed")}</span>
              <div className="role">{t(c.key + "_role")}</div>
              <div className="for">{t(c.key + "_for")}</div>
              <p>{t(c.key + "_p")}</p>
            </div>
          ))}
        </div>
        <p className="pool-note">
          {t("pool_note_a")}
          <b>{t("pool_note_b")}</b>
          {t("pool_note_c")}
        </p>
      </div>
    </section>
  );
}
