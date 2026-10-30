"use client";

import { Suspense, useContext, useLayoutEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { api } from "../../../lib/api";
import { AuthContext } from "../../../components/AuthProvider";
import EnvoyDetail from "../../../components/brief/EnvoyDetail";
import { installMockBriefApi } from "../../../components/brief/demo/mockBriefApi";
import { useTranslations } from "../../../lib/i18n";

// DEMO PAGE: the real proof-ledger / trust-ladder / rehearsal screen against the in-memory fake (mock data only).

function Inner() {
    const tHC = useTranslations("hcTryEnvoydemoDetailPage");
  const auth = useContext(AuthContext) as Record<string, unknown> | null;
  const id = useSearchParams().get("id") || "demo-1";
  const value = { ...(auth ?? {}), isAdmin: true, hasPermission: () => true };
  // Install the fake before the screen mounts and restore the real API when leaving the demo.
  const [ready, setReady] = useState(false);
  useLayoutEffect(() => {
    const restore = installMockBriefApi(api as unknown as Record<string, unknown>);
    setReady(true);
    return restore;
  }, []);
  if (!ready) return null;
  return (
    <AuthContext.Provider value={value as never}>
      <div className="mx-auto max-w-6xl px-4 py-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-2 text-sm">
          <span>
            <strong>{tHC("s3")}</strong> {tHC("s2")}
          </span>
          <Link href="/try/envoy-demo" className="rounded-lg border border-border/70 px-3 py-1 text-xs hover:bg-background/60">
            {tHC("s1")}
          </Link>
        </div>
        <EnvoyDetail id={id} />
      </div>
    </AuthContext.Provider>
  );
}

export default function EnvoyDemoDetailPage() {
  return (
    <Suspense fallback={null}>
      <Inner />
    </Suspense>
  );
}
