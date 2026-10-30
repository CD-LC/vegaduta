"use client";

import { useEffect } from "react";
import Link from "next/link";
import { Home, RotateCcw } from "lucide-react";
import Logo from "./components/Logo";
import { reportClientError } from "./lib/clientLogger";
import { useTranslations } from "./lib/i18n";

// Route-segment error boundary (2026-09-24). Same glass slab as not-found.
// `reset()` re-renders the failed segment; the digest is shown so support can
// find the server log line without the user reading a stack trace.
export default function RouteError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
    const tHC = useTranslations("hcError");
  useEffect(() => {
    // Keep the console line: it is the only place the real stack lands in dev.
    console.error(error);
    // Also keep it server-side (web pod log, type client_error) so a crash can be diagnosed without the user's console.
    reportClientError("client_error", error.message || "render error", {
      source: "route-error-boundary",
      path: typeof window === "undefined" ? "" : window.location.pathname,
      digest: error.digest,
      stack: (error.stack || "").split("\n").slice(0, 6).join(" | "),
    });
  }, [error]);

  return (
    <main className="std-screen">
      <div className="std-screen__card" role="alert">
        <div className="mb-6 flex justify-center">
          <Logo size={30} />
        </div>
        <div className="std-screen__code" aria-hidden="true">
          500
        </div>
        <h1>{tHC("s4")}</h1>
        <p>
          {tHC("s3")}{" "}
          {error.digest ? <span className="mkt-mono">Ref {error.digest}</span> : null}
        </p>
        <details className="mt-4 text-start text-xs text-muted-foreground" data-testid="route-error-details">
          <summary className="cursor-pointer">{tHC("s2")}</summary>
          <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono">
            {error.message}
            {"\n"}
            {(error.stack || "").split("\n").slice(1, 6).join("\n")}
          </pre>
        </details>
        <div className="std-screen__actions">
          <button type="button" onClick={reset} className="std-screen__btn std-screen__btn--primary">
            <RotateCcw className="size-4" aria-hidden="true" />
            {tHC("s1")}
          </button>
          <Link href="/" className="std-screen__btn std-screen__btn--secondary">
            <Home className="size-4" aria-hidden="true" />
            Go home
          </Link>
        </div>
      </div>
    </main>
  );
}
