"use client";

import { Button } from "../../ui/button";
import { Badge } from "../../ui/badge";
import { InspectorHead } from "../ChannelInspectorCards";
import PaginationControls from "../../PaginationControls";
import SentinelsPanel from "../../SentinelsPanel";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function MobileSdkInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    apk,
    busy,
    handleGeneratePairingCode,
    pairingCode,
    devicesLoaded,
    devices,
    devicesPagination,
    handleToggleDeviceHistory,
    expandedDeviceId,
    confirmRevokeDeviceId,
    handleRevokeDeviceClick,
    setConfirmRevokeDeviceId,
    commandsByDevice,
    deviceCommandsPagination,
  } = ctx;
  return (
    <>
      <>
        <InspectorHead channelKey="mobile-sdk" title={t("mobileSdkTitle")} subtitle={t("mobileSdkSubtitle")} />
        {apk && (
          <div className="rounded-xl border border-border bg-muted/40 p-4">
            <Button asChild size="sm" variant="outline">
              <a href={apk.href} download>
                {t("mobileSdkDownloadAndroidButton")}
              </a>
            </Button>
            <p className="mt-2 text-xs leading-relaxed text-muted-foreground">{t("mobileSdkDownloadHint")}</p>
            <p className="mt-1.5 break-all font-mono text-[10px] text-muted-foreground">
              {apk.sha256 && (
                <>
                  {t("mobileSdkDownloadShaLabel")}: {apk.sha256}
                </>
              )}
            </p>
          </div>
        )}
        <Button type="button" size="sm" disabled={busy} onClick={handleGeneratePairingCode}>
          {t("generatePairingCodeButton")}
        </Button>
        {pairingCode && (
          <div className="rounded-xl border border-primary/30 bg-primary/5 p-4 text-center">
            <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
              {t("pairingCodeLabel")}
            </div>
            <div className="mt-1 font-mono text-2xl font-bold tracking-[0.15em] text-foreground">
              {pairingCode.code}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              {t("pairingCodeExpires", { time: new Date(pairingCode.expiresAt).toLocaleTimeString() })}
            </div>
          </div>
        )}
        {devicesLoaded && devices.length === 0 && (
          <p className="text-sm text-muted-foreground">{t("noDevicesPairedYet")}</p>
        )}
        {devicesPagination.pageItems.map((d: any) => (
          <div key={d.id} className="rounded-xl border border-border bg-muted/40 p-4">
            <div className="flex items-center gap-2 font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
              {d.platform}
              {d.revoked && (
                <Badge variant="destructive" className="text-xs">
                  {t("deviceStatusRevoked")}
                </Badge>
              )}
            </div>
            <div className="mt-0.5 text-sm font-semibold text-foreground">{d.deviceName}</div>
            <div className="text-xs text-muted-foreground">
              {d.lastSeenAt ? t("lastSeenTime", { time: new Date(d.lastSeenAt).toLocaleString() }) : t("neverPolled")}
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={() => handleToggleDeviceHistory(d.id)}
              >
                {expandedDeviceId === d.id ? t("hideDeviceHistoryButton") : t("deviceHistoryButton")}
              </Button>
              {!d.revoked && (
                <>
                  <Button
                    type="button"
                    variant={confirmRevokeDeviceId === d.id ? "destructive" : "outline"}
                    size="sm"
                    disabled={busy}
                    onClick={() => handleRevokeDeviceClick(d.id)}
                  >
                    {confirmRevokeDeviceId === d.id ? t("deviceRevokeConfirmButton") : t("deviceRevokeButton")}
                  </Button>
                  {confirmRevokeDeviceId === d.id && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => setConfirmRevokeDeviceId(null)}
                    >
                      {t("deviceRevokeCancelButton")}
                    </Button>
                  )}
                </>
              )}
            </div>
            {expandedDeviceId === d.id && (
              <div className="mt-3 space-y-1 border-t border-border pt-3">
                {(commandsByDevice[d.id] || []).length === 0 && (
                  <div className="font-mono text-xs text-muted-foreground">{t("noDeviceCommandsQueued")}</div>
                )}
                {deviceCommandsPagination.pageItems.map(
                  (cmd: { id: any; commandType: string; status: string; createdAt: string }) => (
                    <div key={cmd.id} className="font-mono text-xs text-muted-foreground">
                      {cmd.commandType} · {cmd.status} · {new Date(cmd.createdAt).toLocaleString()}
                    </div>
                  )
                )}
                <PaginationControls
                  page={deviceCommandsPagination.page}
                  totalPages={deviceCommandsPagination.totalPages}
                  totalItems={deviceCommandsPagination.totalItems}
                  pageSize={deviceCommandsPagination.pageSize}
                  onPageChange={deviceCommandsPagination.setPage}
                  onPageSizeChange={deviceCommandsPagination.setPageSize}
                  itemLabel={t("itemLabelCommands")}
                />
              </div>
            )}
          </div>
        ))}
        <PaginationControls
          page={devicesPagination.page}
          totalPages={devicesPagination.totalPages}
          totalItems={devicesPagination.totalItems}
          pageSize={devicesPagination.pageSize}
          onPageChange={devicesPagination.setPage}
          onPageSizeChange={devicesPagination.setPageSize}
          itemLabel={t("itemLabelDevices")}
        />
        <SentinelsPanel />
      </>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["mobile-sdk"], Inspector: MobileSdkInspector };
