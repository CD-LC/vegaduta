import { NextResponse } from "next/server";
import { loadMessages, messagesVersion } from "../../../lib/messages-loader";
import { DEFAULT_LOCALE, SUPPORTED_LOCALES } from "../../../lib/i18n-config";

// Full translation catalog for one locale, fetched lazily by I18nProvider
// after first paint when the root layout inlined only the route's own
// namespaces (see app/lib/i18n-namespaces.ts). Returns the active locale plus
// the English fallback the provider resolves missing keys against.
//
// Caching: `?v=<content hash>` (messagesVersion) is the cache key. A matching
// version is immutable for a year - the browser (and any CDN) never re-asks
// until a deploy changes the catalog. Without a version, or with a stale one,
// the answer is still correct but marked no-store.
export async function GET(request: Request, ctx: { params: Promise<{ locale: string }> }) {
  const { locale: raw } = await ctx.params;
  const locale = SUPPORTED_LOCALES.some((l) => l.code === raw) ? raw : DEFAULT_LOCALE;
  const [active, en] = await Promise.all([loadMessages(locale), loadMessages(DEFAULT_LOCALE)]);
  const { _sourceHash: _drop, ...messages } = active as Record<string, unknown>;
  const version = await messagesVersion(locale);
  const requested = new URL(request.url).searchParams.get("v");
  const immutable = requested === version;
  return NextResponse.json(
    { locale, version, messages, en: locale === DEFAULT_LOCALE ? null : en },
    {
      headers: {
        "Cache-Control": immutable ? "public, max-age=31536000, immutable" : "no-store",
        Vary: "Accept-Encoding",
      },
    }
  );
}
