import Link from "next/link";
import { notFound } from "next/navigation";
import { UserRound, LibraryBig } from "lucide-react";
import LibraryChrome from "../../components/library/LibraryChrome";
import MySubmissions from "../../components/templates/MySubmissions";
import { isPersonalDenied, isPersonalEdition } from "../../lib/edition";

// "My submissions" - the authed corner of the /templates section. It lives
// under the public /templates prefix (so the gallery is crawlable), which
// means AuthProvider provides NO AuthContext here and AppShell/useAuth would
// throw. LibraryChrome therefore renders the standalone public chrome until a
// Keycloak session is found, then swaps in the console shell (it supplies the
// AuthContext itself); MySubmissions still checks the session on its own and
// prompts sign-in when there isn't one.
export default async function MyTemplatesPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  // Not served on a personal install (the proxy answers 404 first; this is the same answer if it is bypassed).
  if (isPersonalEdition() && isPersonalDenied("/templates/mine")) notFound();
  const sp = await searchParams;
  const autoOpen = sp.new === "1" || "share" in sp;

  return (
    <LibraryChrome eyebrowKey="eyebrowMine">
      <header className="mb-8 flex flex-col gap-4 border-b border-border pb-8 sm:flex-row sm:items-end sm:justify-between">
        <div className="space-y-2">
          <p className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-primary">
            <UserRound className="size-4" aria-hidden="true" />
            My templates
          </p>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
            Templates you&apos;ve shared
          </h1>
          <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
            Draft, submit and track your community templates. Submitted templates are reviewed before they
            appear publicly.
          </p>
        </div>
        <Link
          href="/templates"
          className="inline-flex shrink-0 items-center gap-1.5 rounded-full border border-border bg-card px-3.5 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground"
        >
          <LibraryBig size={15} strokeWidth={2} aria-hidden="true" /> Browse the gallery
        </Link>
      </header>

      <MySubmissions autoOpenSubmit={autoOpen} />
    </LibraryChrome>
  );
}
