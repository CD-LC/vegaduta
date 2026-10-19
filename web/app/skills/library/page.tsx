"use client";

// /skills/library - the skill library: imported, learned and authored skills
// with a review queue. Learned skills arrive as PENDING and do nothing until a
// person approves them. The older /skills page (by-name editor, registry,
// security scan) is untouched and linked from here.

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import SkillLibraryPanel from "../../components/skillLibrary/SkillLibraryPanel";
import { useTranslations } from "../../lib/i18n";

export default function SkillLibraryPage() {
  const t = useTranslations("skillLibrary");
  const [agents, setAgents] = useState<Array<{ id: string; name: string }>>([]);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let alive = true;
    api
      .listAgents()
      .then((list) => {
        if (alive && Array.isArray(list)) setAgents(list.map((a) => ({ id: a.id, name: a.name })));
      })
      .catch(() => {})
      .finally(() => alive && setReady(true));
    return () => {
      alive = false;
    };
  }, []);
  usePageReady(ready);

  const sorted = useMemo(() => [...agents].sort((a, b) => a.name.localeCompare(b.name)), [agents]);

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <p className="mb-4 text-xs text-muted-foreground">
        <Link href="/skills" className="underline underline-offset-2">
          {t("legacyLink")}
        </Link>
      </p>
      <SkillLibraryPanel agents={sorted} />
    </AppShell>
  );
}
