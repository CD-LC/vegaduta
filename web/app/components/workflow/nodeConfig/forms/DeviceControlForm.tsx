"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { DeviceSummary } from "../types";
import type { NodeFormProps } from "../registryTypes";
import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { ScopedExpressionField } from "../../ExpressionField";
import { DEVICE_COMMAND_TYPES_WITHOUT_PAYLOAD, devicePayloadPlaceholder, devicePayloadHint } from "../shared";

export default function DeviceControlForm({ config, setConfig, devices }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
                <div className="field">
                  <Label>{t("deviceLabel")}</Label>
                  <Select
                    value={config.deviceId || ""}
                    onChange={(e) => setConfig({ deviceId: e.target.value })}
                    placeholder={t("selectDevicePlaceholder")}
                    options={(devices || [])
                      .filter((d: DeviceSummary) => !d.revoked)
                      .map((d: DeviceSummary) => ({ value: d.id, label: `${d.deviceName} (${d.platform})` }))}
                  />
                  {(!devices || devices.length === 0) && <p className="ax-wf-empty">{t("noPairedDevicesHint")}</p>}
                </div>
                <div className="field">
                  <Label>{t("commandTypeLabel")}</Label>
                  <Select
                    value={config.commandType || "TAP"}
                    onChange={(e) => setConfig({ commandType: e.target.value })}
                    options={[
                      { value: "TAP", label: t("commandTap") },
                      { value: "TYPE_TEXT", label: t("commandTypeText") },
                      { value: "OPEN_APP", label: t("commandOpenApp") },
                      { value: "SCREENSHOT", label: t("commandScreenshot") },
                      { value: "SWIPE", label: t("commandSwipe") },
                      { value: "SCROLL", label: t("commandScroll") },
                      { value: "READ_SCREEN", label: t("commandReadScreen") },
                      { value: "BACK", label: t("commandBack") },
                      { value: "HOME", label: t("commandHome") },
                      { value: "RECENTS", label: t("commandRecents") },
                      { value: "DISMISS_NOTIFICATION", label: t("commandDismissNotification") },
                      { value: "NOTIFICATION_ACTION", label: t("commandNotificationAction") },
                      { value: "CUSTOM", label: t("commandCustom") },
                    ]}
                  />
                </div>
                {!DEVICE_COMMAND_TYPES_WITHOUT_PAYLOAD.has(config.commandType) && (
                  <div className="field">
                    <Label>{t("payloadLabel")}</Label>
                    <ScopedExpressionField
                      field="payload"
                      placeholder={devicePayloadPlaceholder(t, config.commandType)}
                      value={config.payload || ""}
                      onChange={(v) => setConfig({ payload: v })}
                      ariaLabel={t("payloadLabel")}
                    />
                    <p className="ax-wf-empty">{devicePayloadHint(t, config.commandType)}</p>
                  </div>
                )}
                <p className="ax-wf-empty">{t("deviceFireAndForgetHint")}</p>
    </>
  );
}
