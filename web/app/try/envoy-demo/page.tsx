"use client";

import { useContext, useLayoutEffect, useState } from "react";
import { api } from "../../lib/api";
import { AuthContext } from "../../components/AuthProvider";
import BuildCenter from "../../components/brief/BuildCenter";
import { installMockBriefApi, resetMockBriefApi } from "../../components/brief/demo/mockBriefApi";
import { useTranslations } from "../../lib/i18n";

// DEMO PAGE for the Earned Autonomy screens. It runs the REAL components against an in-memory fake of /api/briefs,
// so every number and result you see is invented for the demo. Public (under /try) so no sign-in is needed.

export default function EnvoyDemoPage() {
    const tHC = useTranslations("hcTryEnvoydemoPage");
  const auth = useContext(AuthContext) as Record<string, unknown> | null;
  const [run, setRun] = useState(0);
  // Install the fake before the console mounts (layout effects run before its data-loading effects) and restore the real API on leave.
  const [ready, setReady] = useState(false);
  useLayoutEffect(() => {
    const restore = installMockBriefApi(api as unknown as Record<string, unknown>);
    setReady(true);
    return restore;
  }, []);
  const value = { ...(auth ?? {}), isAdmin: true, hasPermission: () => true };
  if (!ready) return null;
  return (
    <AuthContext.Provider value={value as never}>
      <div className="mx-auto max-w-6xl px-4 py-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-2 text-sm">
          <span>
            <strong>{tHC("s3")}</strong> {tHC("s2")}
          </span>
          <button
            type="button"
            className="rounded-lg border border-border/70 px-3 py-1 text-xs hover:bg-background/60"
            onClick={() => { resetMockBriefApi(); setRun((n) => n + 1); }}
          >
            {tHC("s1")}
          </button>
        </div>
        <BuildCenter key={run} onOpenAppBuilder={() => undefined} detailHref={(id) => `/try/envoy-demo/detail?id=${id}`} />
      </div>
    </AuthContext.Provider>
  );
}
