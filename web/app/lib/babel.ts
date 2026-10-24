/** Shared lazy loader for the vendored @babel/standalone copy
 * (public/vendored/babel.min.js, written by scripts/vendor-babel.mjs).
 * Loaded on demand so pages pay the 1+ MB parse only when they transpile or
 * syntax-check; no third-party CDN fallback because the enforced CSP does not
 * trust one. Cached so it loads once. */
declare global {
  interface Window {
    Babel?: {
      transform: (
        code: string,
        opts: { filename?: string; presets?: unknown[]; sourceType?: string }
      ) => { code: string };
    };
  }
}

let babelPromise: Promise<NonNullable<Window["Babel"]>> | null = null;

export function loadBabel(): Promise<NonNullable<Window["Babel"]>> {
  if (window.Babel) return Promise.resolve(window.Babel);
  if (babelPromise === null) {
    babelPromise = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = "/vendored/babel.min.js";
      s.onload = () => (window.Babel ? resolve(window.Babel) : reject(new Error("babel empty")));
      s.onerror = () => reject(new Error("cannot load /vendored/babel.min.js"));
      document.head.appendChild(s);
    });
  }
  return babelPromise;
}
