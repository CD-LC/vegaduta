"use client";

import { useTranslations } from "../../lib/i18n";

// The Zerodha-style anti-gimmick pledge band (research digest: "discloses
// what it refuses to do"). All copy via the hire namespace (en.json).
export default function PledgeBand() {
  const t = useTranslations("hire");
  return (
    <div className="pledge">
      <div className="wrap">
        <b>{t("pledge_label")}</b>
        <ul>
          <li>{t("pledge_item1")}</li>
          <li>{t("pledge_item2")}</li>
          <li>{t("pledge_item3")}</li>
          <li>{t("pledge_item4")}</li>
          <li>{t("pledge_item5")}</li>
        </ul>
      </div>
    </div>
  );
}
