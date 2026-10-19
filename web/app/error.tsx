"use client";

import { useEffect } from "react";
import Link from "next/link";
import { Home, RotateCcw } from "lucide-react";
import Logo from "./components/Logo";
import { reportClientError } from "./lib/clientLogger";

// Route-segment error boundary (2026-09-24). Same glass slab as not-found.
// `reset()` re-renders the failed segment; the digest is shown so support can
// find the server log line without the user reading a stack trace.
export default function RouteError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
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
        <div className="std-screen__code" aria-hidden="true">500</div>
        <h1>Something went wrong on this screen.</h1>
        <p>
          Your data is safe. Try again, or head back home. {error.digest ? <span className="mkt-mono">Ref {error.digest}</span> : null}
        </p>
        <details className="mt-4 text-left text-xs text-muted-foreground" data-testid="route-error-details">
          <summary className="cursor-pointer">Technical details</summary>
          <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono">
            {error.message}
            {"\n"}
            {(error.stack || "").split("\n").slice(1, 6).join("\n")}
          </pre>
        </details>
        <div className="std-screen__actions">
          <button type="button" onClick={reset} className="std-screen__btn std-screen__btn--primary">
            <RotateCcw className="size-4" aria-hidden="true" />
            Try again
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
