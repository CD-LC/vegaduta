"use client";

import { useParams } from "next/navigation";
import AppShell from "../../components/AppShell";
import EnvoyDetail from "../../components/brief/EnvoyDetail";
import { useTranslations } from "../../lib/i18n";

/** /build/[id]: one applied envoy - graph, needs, proof ledger, trust ladder, rehearsal. */
export default function EnvoyDetailPage() {
  const t = useTranslations("brief");
  const params = useParams();
  const id = Array.isArray(params.id) ? params.id[0] : (params.id as string);
  return (
    <AppShell title={t("detail.title")} eyebrow={t("tabs.build")} wide>
      <EnvoyDetail id={id} />
    </AppShell>
  );
}
