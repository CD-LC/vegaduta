"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { NodeFormProps } from "../registryTypes";
import { Label } from "../../../ui/label";
import { TEXTAREA_CLASS } from "../shared";

export default function ApprovalForm({ config, setConfig }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
                <p className="ax-wf-empty">
                  {t("approvalPauseHintPrefix")}
                  <code>{t("approvalActionTypeCode")}</code>.
                </p>
                <div className="field">
                  <Label>{t("instructionsForApproverLabel")}</Label>
                  <textarea
                    rows={3}
                    className={TEXTAREA_CLASS}
                    placeholder={t("approvalMessagePlaceholder")}
                    value={config.message || ""}
                    onChange={(e) => setConfig({ message: e.target.value })}
                  />
                  <p className="ax-wf-empty">{t("approvalMessageHint")}</p>
                </div>
                <div className="field">
                  <Label>Ask the approver for (one field per line)</Label>
                  <textarea
                    rows={3}
                    className={TEXTAREA_CLASS}
                    placeholder={"Cost centre\nPayment date"}
                    value={Array.isArray(config.formFields) ? config.formFields.join("\n") : ""}
                    onChange={(e) =>
                      setConfig({
                        formFields: e.target.value
                          .split("\n")
                          .map((l) => l.trimStart())
                          .slice(0, 12),
                      })
                    }
                  />
                  <p className="ax-wf-empty">
                    Optional. The approver fills these in when approving; the answers are passed to the next step.
                  </p>
                </div>
                <label
                  className="row"
                  style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.5rem" }}
                >
                  <input
                    type="checkbox"
                    checked={!!config.adminOnly}
                    onChange={(e) => setConfig({ adminOnly: e.target.checked })}
                  />
                  {t("adminOnlyLabel")}
                </label>
                {config.adminOnly && <p className="ax-wf-empty">{t("adminOnlyEnforcedHint")}</p>}
    </>
  );
}
