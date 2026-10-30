"use client";

import { useEffect } from "react";
import Link from "next/link";
import { Home, RotateCcw } from "lucide-react";
import Logo from "./components/Logo";
import { Button } from "./components/ui/button";
import { Card } from "./components/ui/card";
import { useTranslations } from "../lib/i18n";

// Route-segment error boundary (FE-019, 2026-09-30). Ported from
// web/app/error.tsx but built on web-admin's own shadcn Card/Button tokens.
// `reset()` re-renders the failed segment; the digest is shown so an operator
// can find the matching server log line without reading a stack trace.
export default function RouteError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
    const tHC = useTranslations("hcPlatformadminError");
  useEffect(() => {
    // Keep the console line: it is the only place the real stack lands in dev.
    console.error(error);
  }, [error]);

  return (
    <main className="flex min-h-[60vh] items-center justify-center p-6">
      <Card role="alert" className="w-full max-w-md items-center px-8 text-center">
        <Logo size={28} />
        <div className="font-mono text-5xl font-semibold tracking-tight text-primary" aria-hidden="true">
          500
        </div>
        <h1 className="text-xl font-semibold text-foreground">{tHC("s3")}</h1>
        <p className="text-sm text-muted-foreground">
          {tHC("s2")}{" "}
          {error?.digest ? <span className="font-mono">Ref {error.digest}</span> : null}
        </p>
        <div className="flex flex-wrap justify-center gap-2">
          <Button type="button" onClick={reset}>
            <RotateCcw aria-hidden="true" />
            {tHC("s1")}
          </Button>
          <Button asChild variant="outline">
            <Link href="/platform-admin">
              <Home aria-hidden="true" />
              Dashboard
            </Link>
          </Button>
        </div>
      </Card>
    </main>
  );
}