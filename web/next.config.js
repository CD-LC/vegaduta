// The Content-Security-Policy is NOT set here: headers() below are frozen
// into the routes manifest at `next build`, but the API/auth origins it must
// list are runtime config. proxy.js builds and ENFORCES a nonce + strict-dynamic
// policy per request (app/lib/csp.ts); CSP_REPORT_ONLY=true switches it to
// report-only as the escape hatch.

/** @type {import('next').NextConfig} */
const nextConfig = {
  devIndicators: false,
  // Self-contained server bundle (OSS "one image, any domain", 2026-09-29):
  // `next build` emits .next/standalone/server.js with only the node_modules
  // it traces, so the Docker runtime stage ships that instead of the whole
  // dependency tree. Tracing root pinned to this folder for the same
  // stray-lockfile reason as turbopack.root below (Next warns if they differ).
  // Public config (API/auth URLs etc.) is read at REQUEST time - see
  // app/lib/env.ts and web/docs/RUNTIME-CONFIG.md.
  output: "standalone",
  outputFileTracingRoot: __dirname,
  // No "X-Powered-By: Next.js" - it only tells scanners which CVE list to try.
  poweredByHeader: false,

  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // 2026-09-17: on the compose stack these three came from Caddy
          // (deploy/caddy/Caddyfile); Azure Container Apps + Cloudflare add
          // none of them, so production has served without HSTS / nosniff
          // since the 2026-09-09 cutover (e2e/tests-prod/api-contract.spec.ts
          // has been red on both). Set at the app so every host gets them.
          // No X-Frame-Options on purpose: /a/embed must stay frameable.
          { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // Security audit 2026-09-18. Features the console really uses stay
          // on for this origin (camera/mic: CallPanel + Sight, geolocation:
          // edge weather tool, payment: Razorpay checkout); everything else a
          // framed or injected third party could ask for is off.
          {
            key: "Permissions-Policy",
            value:
              'camera=(self), microphone=(self), geolocation=(self), payment=(self "https://checkout.razorpay.com" "https://api.razorpay.com"), usb=(), serial=(), bluetooth=(), interest-cohort=()',
          },
        ],
      },
      // Clickjacking (security audit 2026-09-18): the per-request policy from
      // proxy.js was report-only at the time, so its frame-ancestors blocked
      // nothing, and there is no
      // X-Frame-Options because /a/embed must stay frameable - so every
      // signed-in console page was frameable by any origin. A second, ENFORCING
      // policy carrying only frame-ancestors is safe: it constrains who may
      // frame us and nothing about what the page itself loads. /a/embed is the
      // one path deliberately left out.
      {
        source: "/((?!a/embed/).*)",
        headers: [{ key: "Content-Security-Policy", value: "frame-ancestors 'self'" }],
      },
      // EDGE CACHING FOR /public ASSETS (Cloudflare cache audit, 2026-09-17).
      //
      // Measured against production BEFORE this change:
      //   /_next/static/chunks/*.js     public, max-age=31536000, immutable
      //                                 cf-cache-status: HIT  (age 5.9 days)
      //   /logo-mark.png, /og-image.png public, max-age=0
      //                                 cf-cache-status: BYPASS
      //   /manifest.webmanifest         public, max-age=0   -> DYNAMIC
      //
      // Next.js already caches its own hashed build output perfectly, so the
      // hashed bundles were never the problem. What it does NOT do is put a
      // useful TTL on anything in public/ - those go out as `max-age=0`, which
      // Cloudflare honours literally, so every favicon, logo and OG image on
      // every page view was a full round trip to the Container App origin.
      //
      // These files only ever change on a deploy, so a shared-cache TTL in days
      // costs nothing. s-maxage (edge) is deliberately far longer than max-age
      // (browser): a replaced asset reaches new visitors on the next purge or
      // within the week, while a visitor who already holds a copy revalidates
      // hourly. NOT `immutable` - unlike /_next/static/* these paths are
      // unhashed, so a replaced file has to be able to win eventually.
      //
      // The (?!_next/) guard matters: without it this rule also matches the
      // hashed fonts under /_next/static/media/ and would DOWNGRADE Next's own
      // year-long immutable header on them. Never widen this pattern to plain
      // "/:all*(...)".
      {
        source: "/((?!_next/).*)\.(png|jpg|jpeg|gif|svg|ico|webp|avif|woff|woff2|ttf|otf)",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=3600, s-maxage=604800, stale-while-revalidate=86400",
          },
        ],
      },
      // /media/* — the landing hero video + poster (landing conversion rebuild,
      // 2026-09-17). demo-60s.webm / .mp4 are by far the largest bytes on "/"
      // and the image rule above deliberately lists no video extensions, so
      // they were leaving the origin as `max-age=0`. Unlike the unhashed
      // logos, a re-cut video ships under a NEW filename (the storyboard
      // script names outputs by duration), so `immutable` is safe here: a
      // repeat visitor skips even the revalidation round trip and the video
      // never competes with the JS bundles again. Cloudflare's edge rule
      // (deploy/cloudflare/cache-rules.json) already caches mp4/webm by
      // extension - this header is what the BROWSER honours. Must stay AFTER
      // the image rule: Next applies every matching rule and the last one
      // wins on a duplicate key, which is how demo-poster.jpg gets this TTL.
      {
        source: "/media/:path*",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=31536000, immutable",
          },
        ],
      },
      // /vendored/* (review FE-050, 2026-09-30): third-party runtimes copied out
      // of node_modules at build time (scripts/vendor-*.mjs) - web-llm 6.6 MB,
      // babel 3.0 MB, kokoro 2.1 MB - previously went out with no TTL at all.
      // Unversioned names (web-llm.js, babel.min.js, kokoro.web.js, the
      // *.version.json stamps) get a day in the browser with background
      // revalidation: they can change on a deploy, and web-llm.js is only a
      // one-line re-export. The version-stamped web-llm-<version>.js it points
      // at never changes under its name, so it is immutable - the rule order
      // matters (last matching rule wins on a duplicate key).
      {
        source: "/vendored/:file*",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=86400, s-maxage=604800, stale-while-revalidate=604800",
          },
        ],
      },
      {
        source: "/vendored/:file(web-llm-\\d+\\.\\d+\\.\\d+[^/]*\\.js)",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=31536000, immutable",
          },
        ],
      },
      // Same reasoning, shorter TTL: these are small text/JSON descriptors that
      // a crawler or the browser's install prompt re-reads, and they are cheap
      // to revalidate. A day at the edge still takes them off the origin.
      // webllm-sw.js is deliberately NOT here - a service worker must stay
      // short-lived or a bad one pins itself into every visitor's browser.
      {
        source: "/:file(manifest.webmanifest|site.webmanifest|llms.txt|humans.txt)",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=3600, s-maxage=86400, stale-while-revalidate=86400",
          },
        ],
      },
    ];
  },

  // /register -> /signup (Cloudflare funnel audit, 2026-09-17).
  //
  // The analytics report listed /register as a zero-visit page. It has no
  // visits because it has never existed: there is no app/register/ route, no
  // link anywhere in the app points at it, and production answers it with a
  // hard 404 (verified by request, 2026-09-17). The real route has always been
  // /signup, which is what the landing page links to (10 places) and what the
  // sitemap advertises.
  //
  // /register is nonetheless the name a person guesses, types, or writes into
  // an external link, and every one of those currently lands on a 404. A 308
  // to the route that does exist costs nothing and passes any accumulated link
  // equity through to /signup instead of dropping it.
  async redirects() {
    return [
      {
        source: "/register",
        destination: "/signup",
        permanent: true,
      },
    ];
  },

  // Client router cache (SPA feel, 2026-09-26). Next 15+ defaults the dynamic
  // TTL to 0, so revisiting a console page re-requested its RSC payload from
  // the server every time. These pages are client components that fetch their
  // own data (see lib/api/core.ts's SWR cache), so the payload is just the
  // shell - safe to reuse. Back/forward is unaffected, and router.refresh()
  // (used after a locale change) still bypasses it.
  experimental: {
    staleTimes: { dynamic: 60, static: 300 },
  },

  reactStrictMode: true,
  // Turbopack's root inference picks wrong when a stray lockfile sits one
  // level up (that broke its React Client Manifest bundling once: bundles
  // built relative to the wrong root can't find AuthProvider.js,
  // signup/page.js, etc.). Pinning root explicitly to this directory is the
  // fix Next.js itself suggests in the dev-server warning, and makes this
  // robust to any stray lockfile that shows up above this folder in the
  // future.
  turbopack: {
    root: __dirname,
    // `onnxruntime-node` MUST NEVER ENTER A CLIENT/WORKER BUNDLE (2026-09-12).
    //
    // This is what broke `next build` repo-wide. The chain, straight out of the
    // webpack error report:
    //   app/edge/page.tsx -> components/edge/EdgeConsole.tsx
    //     -> lib/edge/audio/kokoro.ts -> lib/edge/audio/kokoroWorker.ts
    //       -> kokoro-js -> @huggingface/transformers/dist/transformers.node.mjs
    //         -> onnxruntime-node -> bin/napi-v3/<os>/<arch>/onnxruntime_binding.node
    // Those `.node` files are compiled native binaries, one per platform. The
    // bundler tries to parse them as JavaScript: under webpack that is a hard
    // "Module parse failed: Unexpected character" error, and under Turbopack the
    // build simply never finishes (ACR run cu14 hit the 60-minute ceiling; local
    // runs died with no output at all, which is why this took so long to see).
    //
    // Nothing is at fault in our own code. `kokoro.ts` already does the right
    // thing — `import type` for the message types and
    // `new Worker(new URL("./kokoroWorker.ts", import.meta.url))` for the worker
    // itself. The bug is pure module RESOLUTION: inside a worker bundle,
    // `kokoro-js` resolves to its NODE entry, which hard-depends on
    // onnxruntime-node. The browser path (onnxruntime-web / WASM) is the one we
    // actually want and the one the worker uses at runtime.
    //
    // Aliasing it to false makes the resolver drop the Node backend entirely.
    // Safe because onnxruntime-node can never run in a browser anyway — if this
    // alias ever "breaks" something, that something was already broken.
    // THE ACTUAL DEFECT IS IN kokoro-js's PACKAGING, not in our code.
    // Its package.json `exports` map declares only two conditions:
    //     "node":    "./dist/kokoro.js"
    //     "default": "./dist/kokoro.js"
    // There is NO "browser" condition — even though the package ships a perfectly
    // good `dist/kokoro.web.js` right beside it. So every bundler, in every
    // context including a browser Worker, resolves to the NODE build, which
    // hard-requires onnxruntime-node and its per-platform native binaries.
    // Pointing the alias at the web build is the fix; dropping onnxruntime-node
    // alone is not, because the node entry would still be the thing selected.
    resolveAlias: {
      "kokoro-js": require("path").join(__dirname, "node_modules/kokoro-js/dist/kokoro.web.js"),
      // Belt and braces: nothing in a client bundle may ever pull the Node ONNX
      // backend. onnxruntime-node cannot run in a browser, so if this alias ever
      // "breaks" something, that something was already broken.
      "onnxruntime-node": { browser: "./empty-module.js" },
    },
  },
  // WEBPACK: block onnxruntime-node at the module-factory level.
  //
  // resolve.alias was tried first and does NOT work here: the `new Worker(new
  // URL("./kokoroWorker.ts", import.meta.url))` in lib/edge/audio/kokoro.ts makes
  // webpack compile the worker in a CHILD COMPILER, and the alias added to the
  // parent config never reaches it (verified — the fn runs for isServer=false,
  // the alias lands in config.resolve.alias, and the node build still resolves).
  // IgnorePlugin hooks the module factory instead, so it applies everywhere,
  // child compilers included.
  //
  // Note there is no "prefer the browser build" option available: kokoro-js's
  // exports map declares only `node` and `default`, and BOTH point at
  // dist/kokoro.js, the Node build. So condition tweaking cannot help and the
  // dependency has to be cut.
  webpack: (config, { isServer, webpack }) => {
    if (!isServer) {
      config.plugins.push(new webpack.IgnorePlugin({ resourceRegExp: /^onnxruntime-node$/ }));
      // The native binaries are also reached through an expression-based require
      // inside onnxruntime-node's own binding.js (`bin/napi-v3/ sync ^...`), which
      // IgnorePlugin's request matching does not cover, so drop the .node files too.
      config.plugins.push(new webpack.IgnorePlugin({ resourceRegExp: /\.node$/ }));
    }
    return config;
  },
  // Production CDN hook: when CDN_URL is set (e.g. a Cloudflare/CloudFront
  // distribution or Vercel's own edge network pointed at a custom domain),
  // Next.js serves every hashed build asset under /_next/static/* from that
  // domain instead of this app's own origin - the standard Next.js
  // assetPrefix mechanism, no code changes needed elsewhere. Left undefined
  // (assets served from this app's own origin) unless the env var is set,
  // so this is a no-op until a production deployment actually configures a
  // CDN - nothing here fabricates a CDN that isn't actually wired up.
  assetPrefix: process.env.CDN_URL || undefined,
  // Dev-only safety feature (Next.js 15.3+): the dev server refuses
  // cross-origin requests to its own dev-only resources (HMR, RSC payloads,
  // the on-demand font endpoint, etc.) unless the requesting origin's
  // *hostname* (no scheme) is explicitly allowlisted here. Needed whenever
  // the app is reached through something other than localhost - a Cloudflare
  // Tunnel for local testing being the case this project actually hit.
  // ALLOWED_DEV_ORIGINS is comma-separated so more than one tunnel/hostname
  // can be listed without another code change; unset (the default) means
  // this is a no-op and plain localhost dev is completely unaffected.
  allowedDevOrigins: process.env.ALLOWED_DEV_ORIGINS
    ? process.env.ALLOWED_DEV_ORIGINS.split(",")
        .map((origin) => origin.trim())
        .filter(Boolean)
    : undefined,
  // next/image needs external hostnames allowlisted before it will optimize
  // them. i.ytimg.com serves the YouTube thumbnail facades in
  // genesis/WatchAndLearn.tsx (performance-cwv audit, 2026-07-30) - local
  // /public assets (logo-mark.png etc.) need no entry here.
  images: {
    remotePatterns: [{ protocol: "https", hostname: "i.ytimg.com" }],
  },
};

module.exports = nextConfig;
