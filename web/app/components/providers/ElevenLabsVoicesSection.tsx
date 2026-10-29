"use client";

import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import PaginationControls from "../PaginationControls";
import { useTranslations } from "../../lib/i18n";
import type { ElevenLabsVoices } from "./useElevenLabsVoices";

/**
 * ElevenLabs-only follow-up step on the provider card: an ACTIVE credential alone isn't enough for
 * the speak tool (AgentToolProvider#resolveAudioTools also requires a cloned voice), so the card
 * lists the cloned voices and offers a clone-a-voice form. Clicks are stopped from reaching the
 * card's own select handler.
 */
export default function ElevenLabsVoicesSection({ el }: { el: ElevenLabsVoices }) {
  const t = useTranslations("providersPanel");
  const { pagination } = el;
  return (
    <div className="mt-4 space-y-3" role="presentation" onClick={(e) => e.stopPropagation()}>
      {el.voices.length === 0 ? (
        <p className="text-xs text-muted-foreground">{t("elevenLabsVoicesNeededNote")}</p>
      ) : (
        <ul className="space-y-1.5">
          {pagination.pageItems.map((voice) => (
            <li key={voice.id} className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
              <span className="truncate">
                {voice.name} ·{" "}
                {t("elevenLabsVoiceClonedLabel", { date: new Date(voice.createdAt).toLocaleDateString() })}
              </span>
              <Button
                type="button"
                size="sm"
                variant={el.confirmDeleteVoiceId === voice.id ? "destructive" : "ghost"}
                disabled={el.deletingVoiceId === voice.id}
                onClick={() => el.handleDeleteVoiceClick(voice.id)}
              >
                {el.deletingVoiceId === voice.id
                  ? t("deletingButton")
                  : el.confirmDeleteVoiceId === voice.id
                    ? t("confirmDeleteButton")
                    : t("deleteButton")}
              </Button>
            </li>
          ))}
        </ul>
      )}
      <PaginationControls
        page={pagination.page}
        totalPages={pagination.totalPages}
        totalItems={pagination.totalItems}
        pageSize={pagination.pageSize}
        onPageChange={pagination.setPage}
        onPageSizeChange={pagination.setPageSize}
        itemLabel={t("itemLabelVoices")}
      />
      <form onSubmit={el.handleCloneVoice} className="space-y-2">
        <Label htmlFor="elevenlabs-voice-name" className="sr-only">
          {t("elevenLabsVoiceNamePlaceholder")}
        </Label>
        <Input
          id="elevenlabs-voice-name"
          type="text"
          placeholder={t("elevenLabsVoiceNamePlaceholder")}
          value={el.voiceName}
          onChange={(e) => el.setVoiceName(e.target.value)}
          required
        />
        <Label htmlFor="elevenlabs-voice-file" className="sr-only">
          {t("elevenLabsVoiceFileLabel")}
        </Label>
        <input
          id="elevenlabs-voice-file"
          ref={el.fileInputRef}
          type="file"
          accept="audio/*"
          onChange={(e) => el.setVoiceFile(e.target.files?.[0] || null)}
          className="block w-full text-xs text-muted-foreground file:me-2 file:rounded-md file:border file:border-border file:bg-background file:px-2 file:py-1 file:text-xs file:font-medium file:text-foreground"
          required
        />
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" size="sm" disabled={el.cloneBusy}>
            {el.cloneBusy ? t("elevenLabsCloningButton") : t("elevenLabsCloneButton")}
          </Button>
        </div>
      </form>
    </div>
  );
}
