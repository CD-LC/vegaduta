"use client";

import {
  SUPPORTED_LOCALES,
  DEFAULT_LOCALE,
  INDEXABLE_LOCALES,
  LOCALE_COOKIE,
  localizedPath,
} from "../lib/i18n-config";

// Crawlable links to every language version of a translated route
// (multilingual SEO, 2026-09-19).
//
// LanguageSwitcher is a <button> menu that writes a cookie - invisible to a
// crawler, which follows <a href> and nothing else. hreflang tells a search
// engine the versions are related; plain links are how it (and a visitor with
// no JavaScript) actually gets from one to the other. So this renders real
// anchors, server-side, with `hreflang` on each and `lang` on each label
// (the label is in that language's own script, not the page's).
//
// Only INDEXABLE_LOCALES appear here, by construction: a locale with no URL
// of its own has nothing to link to. The full 30-language list stays in
// LanguageSwitcher. `pathname` must be a LOCALIZED_ROUTES entry; for anything
// else localizedPath() returns the same URL for every language and this would
// be a row of identical links, so pass nothing else.
const LINK_CODES = [DEFAULT_LOCALE, ...INDEXABLE_LOCALES];
const LABELS = Object.fromEntries(SUPPORTED_LOCALES.map((l) => [l.code, l]));

export default function LanguageLinks({
  pathname,
  current,
  className = "",
}: {
  pathname: string;
  current: string;
  className?: string;
}) {
  return (
    <nav aria-label="Languages" className={className}>
      <ul className="flex flex-wrap justify-center gap-x-4 gap-y-2 text-xs text-muted-foreground">
        {LINK_CODES.map((code) => (
          <li key={code}>
            <a
              href={localizedPath(pathname, code)}
              hrefLang={code}
              lang={code}
              dir={LABELS[code]?.dir}
              aria-current={code === current ? "page" : undefined}
              className={
                code === current
                  ? "font-medium text-foreground"
                  : "transition-colors hover:text-foreground"
              }
              // The English version lives at the unprefixed URL, which follows
              // the visitor's cookie. Someone reading Hindi who clicks
              // "English" has to have that cookie changed or the same page
              // comes back in Hindi. Prefixed URLs need no such help - the URL
              // decides, and proxy.js syncs the cookie to it.
              onClick={
                code === DEFAULT_LOCALE
                  ? () => {
                      document.cookie = `${LOCALE_COOKIE}=${code}; path=/; max-age=${60 * 60 * 24 * 365}`;
                    }
                  : undefined
              }
            >
              {LABELS[code]?.label || code}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
