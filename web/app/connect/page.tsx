"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { BookOpen, Check, Mail, MessageCircle, PackageOpen, Phone, Send, Slack, type LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { CONNECT_ITEMS, connectState, type ConnectItem, type StatusInput } from "./connectCatalog";

/**
 * /connect - "connect your world": one page that shows what an agent can be reached on or read from
 * (Telegram, WhatsApp, email, Google, Slack, SMS, your documents), whether each is connected, and
 * the existing page that connects it. Nothing here collects a credential; every card opens the
 * page that already does, with the right channel pre-selected.
 */

const ICONS: Record<ConnectItem["id"], LucideIcon> = {
  telegram: Send,
  whatsapp: MessageCircle,
  email: Mail,
  google: Mail,
  slack: Slack,
  sms: Phone,
  knowledge: BookOpen,
};

export default function ConnectPage() {
  const t = useTranslations("connectHub");
  const [status, setStatus] = useState<StatusInput>({ channels: null, googleConnected: null });

  useEffect(() => {
    let live = true;
    api
      .getChannelsStatus()
      .then((r) => live && setStatus((s) => ({ ...s, channels: r?.channels ?? [] })))
      .catch(() => {});
    api
      .getGoogleConnection()
      .then((c) => live && setStatus((s) => ({ ...s, googleConnected: !!c })))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={t("subtitle")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/agents/bundles">
              <PackageOpen size={14} aria-hidden="true" /> {t("readyMade")}
            </Link>
          </Button>
        }
      />
      <div className="mx-auto w-full max-w-4xl space-y-4">
        <p className="text-sm text-muted-foreground">{t("intro")}</p>
        <div className="grid gap-3 sm:grid-cols-2">
          {CONNECT_ITEMS.map((item) => {
            const Icon = ICONS[item.id];
            const state = connectState(item, status);
            return (
              <Card key={item.id} className="gap-3 py-4" data-testid={`connect-${item.id}`}>
                <CardHeader className="px-4">
                  <CardTitle className="flex flex-wrap items-center gap-2 text-sm">
                    <Icon size={16} aria-hidden="true" /> {t(`items.${item.id}.title`)}
                    {item.easy && <Badge variant="secondary">{t("easiest")}</Badge>}
                    {item.needsPublicUrl && (
                      <Badge variant="outline" className="text-muted-foreground">
                        {t("needsPublicUrl")}
                      </Badge>
                    )}
                    {state === "connected" && (
                      <Badge variant="outline" className="gap-1">
                        <Check size={12} aria-hidden="true" /> {t("connected")}
                      </Badge>
                    )}
                  </CardTitle>
                  <CardDescription>{t(`items.${item.id}.body`)}</CardDescription>
                </CardHeader>
                <CardContent className="px-4">
                  <Button asChild size="sm" variant={state === "connected" ? "outline" : "default"}>
                    <Link href={item.href}>{state === "connected" ? t("manage") : t(`items.${item.id}.cta`)}</Link>
                  </Button>
                </CardContent>
              </Card>
            );
          })}
        </div>
        <p className="text-xs text-muted-foreground">{t("privacy")}</p>
      </div>
    </AppShell>
  );
}
