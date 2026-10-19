"use client";

import Link from "next/link";
import { Plug } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { AssistantConnect } from "./assistantTypes";
import { connectHref, type ConnectId } from "./needs";

/**
 * "Connect first": shown instead of an answer when the request needs a service that is not connected.
 * Each option opens the page that connects it (in a new tab, so the thread stays put); coming back to this
 * tab carries on by itself, and the two buttons cover "I did it, check" and "answer without it".
 */
export default function ConnectCard({
  connect,
  busy,
  onResume,
}: {
  connect: AssistantConnect;
  busy: boolean;
  onResume: (force: boolean) => void;
}) {
  const t = useTranslations("assistant");
  const service = (id: ConnectId) => t(`connectSvc_${id}`);
  const wanted = connect.needs.map((n) => n.options.map(service).join(` ${t("connectOr")} `)).join(", ");
  const options = [...new Set(connect.needs.flatMap((n) => n.options))];
  return (
    <div className="w-full max-w-[85%] rounded-xl border border-border bg-secondary/40 px-4 py-3 text-sm" data-testid="connect-card">
      <div className="flex items-center gap-2 font-medium text-foreground">
        <Plug size={16} className="shrink-0 text-primary" aria-hidden="true" />
        {t("connectTitle")}
      </div>
      <p className="mt-1 text-muted-foreground">{t("connectBody", { services: wanted })}</p>
      {connect.stillMissing && (
        <p className="mt-1 text-xs text-destructive" role="status">
          {t("connectStillMissing")}
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {options.map((id) => (
          <Button key={id} asChild size="sm">
            <Link href={connectHref(id)} target="_blank" rel="noopener">
              {t("connectOpen", { service: service(id) })}
            </Link>
          </Button>
        ))}
        <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => onResume(false)}>
          {t("connectDone")}
        </Button>
        <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => onResume(true)}>
          {t("connectSkip")}
        </Button>
      </div>
    </div>
  );
}
