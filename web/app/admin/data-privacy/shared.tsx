"use client";

import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { channelOffered } from "../../lib/edition";
import { useTranslations } from "../../lib/i18n";

/** Channels a contact or a retention rule can be filed under (core ChannelType). */
export const CHANNELS = [
  "WHATSAPP",
  "MESSENGER",
  "INSTAGRAM",
  "SLACK",
  "DISCORD",
  "X",
  "TELEGRAM",
  "EMAIL",
  "TEAMS",
  "VOICE",
  "SMS",
  "SIGNAL",
];

/** CHANNELS minus the ones this edition does not run (Slack, Discord, X and Teams on a personal install). */
export function offeredChannels(): string[] {
  return CHANNELS.filter((c) => channelOffered(c));
}

export function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "-";
}

export function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** Error panel with a Retry button, used by every data view on the page. */
export function LoadError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const t = useTranslations("privacySso");
  return (
    <Alert variant="destructive">
      <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
      <AlertDescription>
        <p>{message}</p>
        <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRetry}>
          {t("retry")}
        </Button>
      </AlertDescription>
    </Alert>
  );
}
