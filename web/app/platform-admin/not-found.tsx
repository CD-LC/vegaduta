import Link from "next/link";
import { Home } from "lucide-react";
import Logo from "./components/Logo";
import { Button } from "./components/ui/button";
import { Card } from "./components/ui/card";

// Standard 404 (FE-019, 2026-09-30). Ported from web/app/not-found.tsx onto
// web-admin's own Card/Button tokens. Inline English on purpose - this app
// has no i18n layer.
export default function NotFound() {
  return (
    <main className="flex min-h-[60vh] items-center justify-center p-6">
      <Card className="w-full max-w-md items-center px-8 text-center">
        <Logo size={28} />
        <div className="font-mono text-5xl font-semibold tracking-tight text-primary" aria-hidden="true">
          404
        </div>
        <h1 className="text-xl font-semibold text-foreground">This admin page does not exist.</h1>
        <p className="text-sm text-muted-foreground">The link may be old, or the page moved. Nothing on the platform was touched.</p>
        <Button asChild>
          <Link href="/platform-admin">
            <Home aria-hidden="true" />
            Dashboard
          </Link>
        </Button>
      </Card>
    </main>
  );
}