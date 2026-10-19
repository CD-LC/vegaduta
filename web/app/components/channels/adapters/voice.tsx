"use client";

import { api } from "../../../lib/api";
import Select from "../../Select";
import { Button } from "../../ui/button";
import { Input } from "../../ui/input";
import { ChannelHealthCard, InboundRoutingCard, InspectorHead } from "../ChannelInspectorCards";
import { errorMessage } from "../../../lib/errors";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function VoiceInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    voiceConnected,
    agents,
    voiceDefaultAgentId,
    cardsByKey,
    statusLoaded,
    setVoiceDefaultAgentId,
    setBusy,
    setError,
    setSuccess,
    refreshStatus,
    busy,
    dtmfLoaded,
    DTMF_DIGITS,
    dtmfDraft,
    dtmfMenu,
    handleSaveDtmfMapping,
    setDtmfDraftAgent,
    setDtmfDraftLabel,
    dtmfBusyDigit,
    handleClearDtmfMapping,
  } = ctx;
  return (
    <>
      <>
        <InspectorHead channelKey="voice" title={t("voiceTitle")} subtitle={t("voiceSubtitle")} />
        {!voiceConnected && <p className="text-sm text-muted-foreground">{t("voiceNeedsTwilioHint")}</p>}
        {/* Same always-visible routing card as every other channel - the
                Twilio connection only gates the DTMF keypad menu below, not
                who answers inbound calls. */}
        <InboundRoutingCard
          t={t}
          agents={agents}
          value={voiceDefaultAgentId}
          savedValue={cardsByKey.get("voice")?.defaultAgentId}
          loaded={statusLoaded}
          onChange={setVoiceDefaultAgentId}
          onSave={async (e) => {
            e.preventDefault();
            if (!voiceDefaultAgentId) return;
            setBusy(true);
            setError(null);
            setSuccess(null);
            try {
              await api.setVoiceDefaultAgent(voiceDefaultAgentId);
              setSuccess(t("voiceDefaultAgentUpdatedSuccess"));
              refreshStatus();
            } catch (err) {
              setError(errorMessage(err, t("setDefaultAgentError")));
            } finally {
              setBusy(false);
            }
          }}
          busy={busy}
        />
        {voiceConnected && (
          <>
            <ChannelHealthCard channelKey="voice" t={t} />
            <div className="rounded-xl border border-border bg-muted/40 p-4">
              <div className="text-sm font-semibold text-foreground">{t("dtmfMenuTitle")}</div>
              <p className="mb-3 mt-1 text-xs text-muted-foreground">{t("dtmfMenuHint")}</p>
              <div className="space-y-2">
                {dtmfLoaded &&
                  DTMF_DIGITS.map((digit: string) => {
                    const draft = dtmfDraft(digit);
                    const hasMapping = dtmfMenu.some((m: any) => m.digit === digit);
                    return (
                      <form
                        key={digit}
                        className="flex flex-wrap items-center gap-2"
                        onSubmit={(e) => {
                          e.preventDefault();
                          void handleSaveDtmfMapping(digit);
                        }}
                      >
                        <span className="flex size-7 shrink-0 items-center justify-center rounded-md border border-border bg-background font-mono text-sm font-bold text-foreground">
                          {digit}
                        </span>
                        <Select
                          className="select-inline"
                          value={draft.agentId}
                          onChange={(e) => setDtmfDraftAgent(digit, e.target.value)}
                          placeholder={t("chooseAgentPlaceholder")}
                          options={agents.map((a: any) => ({ value: a.id, label: a.name }))}
                        />
                        <Input
                          className="h-8 max-w-[140px] text-xs"
                          placeholder={t("dtmfDigitLabel")}
                          value={draft.label}
                          onChange={(e) => setDtmfDraftLabel(digit, e.target.value)}
                        />
                        <Button
                          type="submit"
                          variant="outline"
                          size="sm"
                          disabled={dtmfBusyDigit === digit || !draft.agentId}
                        >
                          {t("dtmfSaveButton")}
                        </Button>
                        {hasMapping && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            disabled={dtmfBusyDigit === digit}
                            onClick={() => handleClearDtmfMapping(digit)}
                          >
                            {t("dtmfClearButton")}
                          </Button>
                        )}
                      </form>
                    );
                  })}
              </div>
            </div>
          </>
        )}
      </>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["voice"], Inspector: VoiceInspector };
