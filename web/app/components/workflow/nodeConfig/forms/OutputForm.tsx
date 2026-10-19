"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { NodeFormProps } from "../registryTypes";
import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { ScopedExpressionField } from "../../ExpressionField";
import WhatsAppTemplateFields from "../../WhatsAppTemplateFields";

export default function OutputForm({ config, setConfig }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
              <div className="field">
                <Label>{t("sendOutputToLabel")}</Label>
                <Select
                  data-testid="output-channel-select"
                  value={config.channel || "log"}
                  onChange={(e) => setConfig({ channel: e.target.value })}
                  options={[
                    { value: "log", label: t("outputChannelLog") },
                    { value: "file", label: t("outputChannelFile") },
                    { value: "email", label: t("outputChannelEmail") },
                    { value: "telegram", label: t("outputChannelTelegram") },
                    { value: "whatsapp", label: t("outputChannelWhatsapp") },
                    { value: "google_drive", label: t("outputChannelGoogleDrive") },
                    { value: "event", label: t("outputChannelEvent") },
                  ]}
                />

                {config.channel === "file" && (
                  <div style={{ marginTop: "0.5rem" }}>
                    <Label>{t("fileTypeLabel")}</Label>
                    <Select
                      data-testid="output-filetype-select"
                      value={config.fileType || "text"}
                      onChange={(e) => {
                        const fileType = e.target.value;
                        // Keep the file name's extension in sync with the chosen
                        // type so the download endpoint (which picks PDF vs. plain
                        // text purely off the file's extension) always matches
                        // what was actually generated - never a silent mismatch
                        // like a ".txt" name holding real PDF bytes.
                        const base = (config.fileName || "workflow-output").replace(/\.(txt|pdf)$/i, "");
                        const fileName = `${base}.${fileType === "pdf" ? "pdf" : "txt"}`;
                        setConfig({ fileType, fileName });
                      }}
                      options={[
                        { value: "text", label: t("fileTypeText") },
                        { value: "pdf", label: t("fileTypePdf") },
                      ]}
                    />
                    <Label className="mt-2">{t("fileNameLabel")}</Label>
                    <ScopedExpressionField
                      field="fileName"
                      placeholder={config.fileType === "pdf" ? "workflow-output.pdf" : "workflow-output.txt"}
                      value={config.fileName || ""}
                      onChange={(v) => setConfig({ fileName: v })}
                      ariaLabel={t("fileNameLabel")}
                    />
                    {config.fileType === "pdf" && <p className="ax-wf-empty">{t("pdfHint")}</p>}
                  </div>
                )}

                {config.channel === "email" && (
                  <div style={{ marginTop: "0.5rem" }}>
                    <Label>{t("emailToLabel")}</Label>
                    <ScopedExpressionField
                      field="emailTo"
                      placeholder="you@example.com"
                      value={config.emailTo || ""}
                      onChange={(v) => setConfig({ emailTo: v })}
                      ariaLabel={t("emailToLabel")}
                    />
                    <p className="ax-wf-empty">{t("emailHint")}</p>
                  </div>
                )}

                {config.channel === "telegram" && (
                  <div style={{ marginTop: "0.5rem" }}>
                    <Label>{t("telegramChatIdLabel")}</Label>
                    <ScopedExpressionField
                      field="telegramChatId"
                      placeholder={t("telegramChatIdPlaceholder")}
                      value={config.telegramChatId || ""}
                      onChange={(v) => setConfig({ telegramChatId: v })}
                      ariaLabel={t("telegramChatIdLabel")}
                    />
                    <p className="ax-wf-empty">{t("telegramHint")}</p>
                  </div>
                )}

                {config.channel === "whatsapp" && (
                  <div style={{ marginTop: "0.5rem" }}>
                    <Label>{t("whatsappNumberLabel")}</Label>
                    <ScopedExpressionField
                      field="whatsappTo"
                      placeholder="+15551234567"
                      value={config.whatsappTo || ""}
                      onChange={(v) => setConfig({ whatsappTo: v })}
                      ariaLabel={t("whatsappNumberLabel")}
                    />
                    <p className="ax-wf-empty">{t("whatsappHint")}</p>
                    <WhatsAppTemplateFields config={config} setConfig={setConfig} />
                  </div>
                )}

                {config.channel === "google_drive" && (
                  <div style={{ marginTop: "0.5rem" }}>
                    <Label>{t("driveFolderIdLabel")}</Label>
                    <ScopedExpressionField
                      field="driveFolderId"
                      placeholder={t("driveFolderIdPlaceholder")}
                      value={config.driveFolderId || ""}
                      onChange={(v) => setConfig({ driveFolderId: v })}
                      ariaLabel={t("driveFolderIdLabel")}
                    />
                    <Label className="mt-2">{t("fileNameLabel")}</Label>
                    <ScopedExpressionField
                      field="driveFileName"
                      placeholder={t("driveFileNamePlaceholder")}
                      value={config.driveFileName || ""}
                      onChange={(v) => setConfig({ driveFileName: v })}
                      ariaLabel={t("fileNameLabel")}
                    />
                    <p className="ax-wf-empty">{t("driveHint")}</p>
                  </div>
                )}
              </div>
    </>
  );
}
