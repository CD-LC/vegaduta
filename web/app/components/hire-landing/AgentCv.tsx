"use client";

import { useTranslations } from "../../lib/i18n";
import { useGeo } from "../../lib/geo";
import type { BizKey } from "./bizData";
import { bizKey } from "./bizData";
import BrandText from "../BrandText";

// Document 1 of 5 — the candidate's CV. Every line matches the verified-claims
// fact base (research-digest.md): 22 scheduled languages + English, console in
// 30 locales "translations rolling out", 9 channels, knowledge formats + cited
// sources, Academy 127 lessons, 13 workflow node types, 38 built-in tools,
// 30,000-connector MCP catalog, 14 BYOK providers + hosted pool.
// The first Skills line and the "for ..." lede personalize from the interview.
export default function AgentCv({ biz }: { biz: BizKey | null }) {
  const t = useTranslations("hire");
  const { variant } = useGeo();
  const isIndia = variant === "india";
  const CHIPS = [
    "cv_chip_whatsapp",
    "cv_chip_phone",
    "cv_chip_telegram",
    "cv_chip_email",
    "cv_chip_slack",
    "cv_chip_discord",
    "cv_chip_instagram",
    "cv_chip_messenger",
    "cv_chip_x",
  ];
  return (
    <section id="cv">
      <div className="wrap">
        <p className="doc-eyebrow">{t("cv_eyebrow")}</p>
        <h2 className="serif">{t("cv_title")}</h2>
        <p className="lede">
          <BrandText text={t("cv_lede_a")} />
          <span>{t(bizKey("cvFor", biz))}</span>
          {t("cv_lede_b")}
        </p>
        <div className="doc" style={{ marginTop: 34 }}>
          <div className="doc-inner">
            <div className="letterhead">
              <b>{t("cv_letterhead")}</b>
              <span>{t("cv_letterheadSub")}</span>
            </div>
            <div className="cv-grid">
              <div className="cv-block">
                <h3>{t("cv_langHeading")}</h3>
                <ul>
                  <li>
                    {t(isIndia ? "cv_lang1IN" : "cv_lang1")} <small>{t("cv_lang1Small")}</small>
                  </li>
                  <li>{t(isIndia ? "cv_lang2IN" : "cv_lang2")}</li>
                  <li>
                    {t("cv_lang3")} <small>{t("cv_lang3Small")}</small>
                  </li>
                </ul>
              </div>
              <div className="cv-block">
                <h3>{t("cv_whereHeading")}</h3>
                <ul>
                  <li>{t("cv_where1")}</li>
                </ul>
                <div className="chips">
                  {CHIPS.map((key) => (
                    <span key={key}>{t(key)}</span>
                  ))}
                </div>
              </div>
              <div className="cv-block">
                <h3>{t("cv_eduHeading")}</h3>
                <ul>
                  <li>
                    {t("cv_edu1_pre")} <em>{t("cv_edu1_em")}</em> {t("cv_edu1_post")}{" "}
                    <small>{t("cv_edu1Small")}</small>
                  </li>
                  <li>{t("cv_edu2")}</li>
                  <li>{t("cv_edu3")}</li>
                </ul>
              </div>
              <div className="cv-block">
                <h3>{t("cv_skillsHeading")}</h3>
                <ul>
                  <li>
                    {t(bizKey("skillMain", biz))} <small>{t(bizKey("skillSmall", biz))}</small>
                  </li>
                  <li>
                    {t("cv_skill2")} <small>{t("cv_skill2Small")}</small>
                  </li>
                  <li>
                    {t("cv_skill3")} <small>{t("cv_skill3Small")}</small>
                  </li>
                </ul>
              </div>
              <div className="cv-refs">
                <b>{t("cv_refs_b")}</b>
                {t("cv_refs_rest")}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
