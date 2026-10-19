/* =============================================================================
 * VegaDuta embeddable chat widget - loader script.
 *
 * WHAT THIS IS
 * A customer pastes ONE tag into their own site:
 *
 *   <script src="https://app.example.com/widget/vegaduta-widget.js"
 *           data-share-id="Xk3pQ..."
 *           data-accent="#0f766e"
 *           data-position="bottom-right"
 *           data-greeting="Hi! Ask me anything about our pricing."
 *           defer></script>
 *
 * ...and gets a launcher bubble in the corner that opens a chat panel wired to
 * their own already-shared agent. Nothing else is required of them: no build
 * step, no npm package, no API key, no CSS to include.
 *
 * WHY THIS FILE IS PLAIN, UNBUNDLED ES5-ish JAVASCRIPT
 * It lives in web/public/, which Next.js serves byte-for-byte without passing
 * it through the bundler. That is deliberate. A widget loader is the one asset
 * in this repo that runs inside SOMEBODY ELSE'S page, on browsers we do not
 * choose, and it must not carry a framework, a polyfill runtime, or a hashed
 * chunk name that changes every deploy - the customer pasted a literal URL into
 * their HTML and that URL has to keep working forever. So: no imports, no JSX,
 * no optional chaining, no build output. Everything below is hand-written and
 * self-contained.
 *
 * WHY AN IFRAME AND NOT AN INLINE SHADOW-DOM CHAT UI
 * Both were considered. The iframe wins on two counts that matter more than the
 * extra ~40KB it costs:
 *
 *   1. CORS. core's CORS allowlist (core/app/.../common/WebConfig.java) is an
 *      EXACT-MATCH first-party allowlist - the deployment's own app/admin
 *      origins. There is no wildcard and no pattern.
 *      A chat UI rendered directly on customer.example.com would call
 *      api.example.com from customer.example.com's origin and be rejected at
 *      preflight, every single time. Rendering the chat INSIDE an iframe whose
 *      document origin is app.example.com means the call to api.example.com
 *      comes from an origin that is ALREADY on the allowlist. The widget
 *      therefore needs no change to the API's origin policy at all - the
 *      allowlist stays closed, and we never have to answer "why does the public
 *      API now accept Origin: *".
 *
 *   2. Style isolation, in both directions. This app's design tokens are
 *      :root-scoped and globals.css carries a bare `button {}` rule that forces
 *      padding on every button on the page. Injected inline, that leaks onto
 *      the customer's own buttons. An iframe is a separate document; neither
 *      side can reach the other's CSS.
 *
 * The launcher itself (the bubble, the panel frame, the unread dot) IS inline,
 * inside a closed-off shadow root, because it has to sit in the host page's
 * layout. It carries its own complete style block and never inherits from the
 * host - see buildStyles() for the specific properties that are re-declared.
 *
 * ############################################################################
 * # KNOWN BLOCKER - READ THIS BEFORE CLAIMING THE WIDGET WORKS               #
 * #                                                                          #
 * # deploy/caddy/Caddyfile (and Caddyfile.gcp) send                          #
 * #     X-Frame-Options "SAMEORIGIN"                                         #
 * # on the `handle @web` block that serves this Next.js app. That header      #
 * # makes EVERY third-party site's browser refuse to render the iframe below. #
 * # Until that header is scoped away from /a/embed/*, this widget works on    #
 * # pages served from app.example.com itself (including                      #
 * # /widget/demo.html) and NOWHERE ELSE.                                      #
 * #                                                                          #
 * # deploy/ is outside this workstream's file ownership, so the header was    #
 * # NOT changed here. See web/public/widget/README.md for the exact edit      #
 * # required and why X-Frame-Options must be replaced with a                  #
 * # Content-Security-Policy frame-ancestors directive rather than simply      #
 * # deleted.                                                                  #
 * ############################################################################
 *
 * RULE 0 (CLAUDE.md): no localhost, ever, in a browser-facing URL. This file
 * has NO baked base URL at all - it derives its own origin from the <script
 * src> that loaded it (see resolveOrigin). That is structurally stronger than
 * a NEXT_PUBLIC_* constant: NEXT_PUBLIC_API_BASE_URL is baked at BUILD time and
 * defaults to http://localhost:8080 in web/Dockerfile, so a base-only
 * `docker compose build web` would have shipped a widget that silently fails
 * for 100% of a customer's visitors. Deriving from the script tag makes that
 * failure mode impossible to introduce, and lets one build serve both
 * .xyz (staging) and .ai (production) unchanged.
 * ========================================================================== */
(function () {
  "use strict";

  /* Protocol version. Bumped only when the postMessage contract below changes
   * shape. Passed to the framed page as ?v= so the two halves can detect a
   * skew (an old cached loader against a freshly deployed page) instead of
   * failing silently. The React side's counterpart constant lives in
   * web/app/components/widget/embedProtocol.ts and MUST be kept equal - the
   * two files cannot import each other (one is unbundled static JS, the other
   * is TypeScript inside the Next build graph), so this is a hand-maintained
   * pair. Both files say so, in both directions. */
  var PROTOCOL_VERSION = 1;

  /* Path fragment used to find our own <script> tag when document.currentScript
   * is unavailable (it is null inside module scripts and inside callbacks). */
  var SCRIPT_PATH = "/widget/vegaduta-widget.js";

  /* postMessage envelope discriminators. Every message in both directions
   * carries one of these as `source`, so a page hosting several unrelated
   * widgets never mistakes somebody else's traffic for ours. */
  var MSG_FROM_FRAME = "vegaduta-widget";
  var MSG_FROM_HOST = "vegaduta-widget-host";

  /* One widget per page. A customer who pastes the tag into both their header
   * partial and their footer partial should get one launcher, not two stacked
   * on top of each other - and the second load must not clobber the first
   * one's state. */
  if (window.__vegaDutaWidgetLoaded) {
    return;
  }
  window.__vegaDutaWidgetLoaded = true;

  // ---------------------------------------------------------------------------
  // Configuration
  // ---------------------------------------------------------------------------

  /** The <script> element that loaded this file, or null if it cannot be found. */
  function currentScriptEl() {
    if (document.currentScript) return document.currentScript;
    var scripts = document.getElementsByTagName("script");
    for (var i = scripts.length - 1; i >= 0; i--) {
      var src = scripts[i].getAttribute("src") || "";
      if (src.indexOf(SCRIPT_PATH) !== -1) return scripts[i];
    }
    return null;
  }

  /** data-* reader with a default. Empty string counts as "not set". */
  function attr(el, name, fallback) {
    if (!el) return fallback;
    var value = el.getAttribute(name);
    return value === null || value === "" ? fallback : value;
  }

  /* Boolean data-* reader. Reads the attribute DIRECTLY rather than through
   * attr(): attr() treats an empty string as "not set" (right for the string
   * options), but for booleans a bare HTML attribute (<script ... data-auto-open>)
   * reads as "" and conventionally means TRUE. */
  function attrBool(el, name, fallback) {
    if (!el) return fallback;
    var raw = el.getAttribute(name);
    if (raw === null) return fallback;
    return raw === "true" || raw === "1" || raw === "";
  }

  /* Accent colour allowlist. The value is written straight into a style
   * declaration inside our shadow root AND forwarded to the framed page as a
   * query parameter, so it must not be arbitrary CSS. Hex, rgb()/rgba(),
   * hsl()/hsla() and a bare CSS colour keyword are the shapes a customer will
   * actually paste; anything else falls back to the brand accent rather than
   * being injected. Deliberately a regex over a parser: the set of legal values
   * is small and closed, and a parser would be more code to audit than the
   * feature is worth. */
  var ACCENT_RE = /^(#[0-9a-fA-F]{3}|#[0-9a-fA-F]{4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|rgba?\(\s*[\d.,%\s/]+\)|hsla?\(\s*[\d.,%\s/deg]+\)|[a-zA-Z]{3,24})$/;

  function safeAccent(raw, fallback) {
    if (!raw) return fallback;
    var trimmed = String(raw).trim();
    return ACCENT_RE.test(trimmed) ? trimmed : fallback;
  }

  /* Default launcher accent when the customer sets none. Matches --accent-500
   * in web/app/globals.css (the AXON indigo-violet brand default) so an
   * unconfigured widget looks like the product it came from. Hardcoded rather
   * than read from a token because this element lives in a shadow root on
   * somebody else's page and has no access to our stylesheet. */
  var DEFAULT_ACCENT = "#6d5bff";

  var scriptEl = currentScriptEl();

  var config = {
    shareId: attr(scriptEl, "data-share-id", ""),
    position: attr(scriptEl, "data-position", "bottom-right"),
    accent: safeAccent(attr(scriptEl, "data-accent", null), DEFAULT_ACCENT),
    greeting: attr(scriptEl, "data-greeting", ""),
    title: attr(scriptEl, "data-title", ""),
    theme: attr(scriptEl, "data-theme", "auto"),
    launcherLabel: attr(scriptEl, "data-launcher-label", "Chat with us"),
    // Distance from the viewport edges, in px. One number drives both axes -
    // two separate knobs is more configuration than anyone has asked for.
    offset: parseInt(attr(scriptEl, "data-offset", "20"), 10),
    // Host pages with their own fixed chrome (sticky headers, cookie bars) sit
    // at wildly different stacking levels; 2147483000 is just below the int32
    // ceiling so we win by default, but it is overridable for the page that
    // genuinely needs its cookie banner on top of us.
    zIndex: parseInt(attr(scriptEl, "data-z-index", "2147483000"), 10),
    autoOpen: attrBool(scriptEl, "data-auto-open", false),
    // Escape hatch for a host that serves this script from a CDN/proxy path
    // where the derived origin would be wrong. Explicitly NOT defaulted to
    // anything - see the Rule 0 note in the file header.
    baseUrl: attr(scriptEl, "data-base-url", ""),
  };

  if (!config.shareId) {
    // Loud, one-line, actionable. A silent no-op here is the single most
    // likely thing to waste a customer's afternoon.
    console.error(
      "[VegaDuta widget] Missing data-share-id on the <script> tag. " +
        "Copy the embed snippet from your agent's Share panel."
    );
    return;
  }

  if (config.position !== "bottom-left") config.position = "bottom-right";
  if (config.theme !== "light" && config.theme !== "dark") config.theme = "auto";
  if (!isFinite(config.offset) || config.offset < 0) config.offset = 20;
  if (!isFinite(config.zIndex)) config.zIndex = 2147483000;

  // ---------------------------------------------------------------------------
  // Origin resolution - the Rule 0 surface
  // ---------------------------------------------------------------------------

  /** Hosts that must never end up in a widget URL served to a real visitor. */
  function isLoopback(hostname) {
    return (
      hostname === "localhost" ||
      hostname === "127.0.0.1" ||
      hostname === "0.0.0.0" ||
      hostname === "[::1]" ||
      hostname === "::1"
    );
  }

  /**
   * The origin this widget's iframe and assets are served from.
   *
   * Precedence: explicit data-base-url, then the origin of our own <script
   * src>. There is deliberately NO third fallback - a hardcoded default would
   * either be wrong for one of the two environments (.xyz vs .ai) or would
   * reintroduce exactly the localhost default that Rule 0 exists to prevent.
   * If neither source resolves, the widget refuses to render and says why.
   */
  function resolveOrigin() {
    if (config.baseUrl) {
      try {
        return new URL(config.baseUrl, window.location.href).origin;
      } catch (e) {
        console.error("[VegaDuta widget] data-base-url is not a valid URL:", config.baseUrl);
        return null;
      }
    }
    var src = scriptEl && (scriptEl.src || scriptEl.getAttribute("src"));
    if (!src) return null;
    try {
      return new URL(src, window.location.href).origin;
    } catch (e) {
      return null;
    }
  }

  var WIDGET_ORIGIN = resolveOrigin();

  if (!WIDGET_ORIGIN) {
    console.error(
      "[VegaDuta widget] Could not determine where to load the chat from. " +
        "Load this script with an absolute src, e.g. " +
        '<script src="https://app.example.com' + SCRIPT_PATH + '" data-share-id="...">.'
    );
    return;
  }

  /* Rule 0 tripwire. Deriving the origin from the script tag makes a baked
   * localhost impossible, but a developer CAN still point a public page at a
   * loopback build by hand. That combination - host page on a real domain,
   * widget origin on loopback - is broken for every visitor who is not sitting
   * at that machine, and it fails invisibly (the iframe just never loads). Warn
   * loudly rather than pretending it works. A loopback host page loading a
   * loopback widget is ordinary local development and stays quiet. */
  try {
    var widgetHost = new URL(WIDGET_ORIGIN).hostname;
    if (isLoopback(widgetHost) && !isLoopback(window.location.hostname)) {
      console.error(
        "[VegaDuta widget] This page is served from " +
          window.location.origin +
          " but the widget is pointed at " +
          WIDGET_ORIGIN +
          ", which only resolves on the developer's own machine. " +
          "No visitor will be able to load the chat. Point data-base-url (or the " +
          "script src) at the public domain instead."
      );
    }
  } catch (e) {
    /* URL parsing of an origin we just built cannot realistically fail; if it
     * does, the tripwire is the least important thing here. */
  }

  // ---------------------------------------------------------------------------
  // The framed chat URL
  // ---------------------------------------------------------------------------

  /**
   * Builds the /a/embed/<shareId> URL for the iframe.
   *
   * parentOrigin is passed so the framed page can address its postMessage
   * replies to exactly one origin instead of "*". Note this is NOT a security
   * boundary in the "prove who you are" sense - the host page controls the
   * attribute and could lie about it - but it is what stops our own messages
   * from being broadcast to whatever other frames happen to be listening, and
   * the framed page carries nothing secret in the first place (the share id is
   * already public, sitting in the customer's HTML source).
   *
   * window.location.origin is the string "null" for a file:// page. Sending
   * that would produce a postMessage target no browser accepts, so it is
   * dropped and the framed page falls back to not posting at all.
   */
  function buildFrameUrl() {
    var params = [];
    function add(key, value) {
      if (value === null || value === undefined || value === "") return;
      params.push(encodeURIComponent(key) + "=" + encodeURIComponent(value));
    }
    add("v", String(PROTOCOL_VERSION));
    if (window.location.origin && window.location.origin !== "null") {
      add("parentOrigin", window.location.origin);
    }
    add("accent", config.accent === DEFAULT_ACCENT ? "" : config.accent);
    add("greeting", config.greeting);
    add("title", config.title);
    add("theme", config.theme === "auto" ? "" : config.theme);
    return (
      WIDGET_ORIGIN +
      "/a/embed/" +
      encodeURIComponent(config.shareId) +
      (params.length ? "?" + params.join("&") : "")
    );
  }

  // ---------------------------------------------------------------------------
  // Shadow DOM chrome
  // ---------------------------------------------------------------------------

  var host = null; // the element we append to <body>
  var root = null; // its shadow root
  var launcher = null;
  var panel = null;
  var frame = null;
  var unreadDot = null;
  var open = false;
  var frameReady = false;

  /* Inherited CSS properties (font, colour, line-height, letter-spacing,
   * direction, text-transform...) cross the shadow boundary. Everything the
   * launcher renders therefore re-declares them explicitly, so a host page with
   * `body { font-family: Papyrus; text-transform: uppercase }` cannot reshape
   * our button. Non-inherited properties (padding, border, background) are
   * already isolated by the shadow root and are set here only because we want
   * them, not defensively. */
  function buildStyles() {
    var edge = config.position === "bottom-left" ? "left" : "right";
    return (
      ":host{all:initial;}" +
      ".wrap{" +
      "position:fixed;bottom:" +
      config.offset +
      "px;" +
      edge +
      ":" +
      config.offset +
      "px;z-index:" +
      config.zIndex +
      ";" +
      "font-family:ui-sans-serif,system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;" +
      "font-size:15px;line-height:1.4;letter-spacing:normal;text-transform:none;font-weight:400;" +
      "color:#111827;direction:ltr;text-align:left;" +
      "display:flex;flex-direction:column;align-items:" +
      (edge === "left" ? "flex-start" : "flex-end") +
      ";gap:12px;}" +
      /* Launcher */
      ".launcher{all:unset;box-sizing:border-box;display:inline-flex;align-items:center;justify-content:center;" +
      "width:56px;height:56px;border-radius:9999px;cursor:pointer;position:relative;" +
      "background:" +
      config.accent +
      ";color:#fff;" +
      "box-shadow:0 10px 30px -8px rgba(10,12,18,.45),0 2px 6px rgba(10,12,18,.18);" +
      "transition:transform .18s ease,box-shadow .18s ease;}" +
      ".launcher:hover{transform:translateY(-2px);box-shadow:0 16px 40px -10px rgba(10,12,18,.5),0 3px 8px rgba(10,12,18,.2);}" +
      ".launcher:focus-visible{outline:3px solid " +
      config.accent +
      ";outline-offset:3px;}" +
      ".launcher svg{width:26px;height:26px;display:block;}" +
      ".icon-close{display:none;}" +
      '.wrap[data-open="true"] .icon-open{display:none;}' +
      '.wrap[data-open="true"] .icon-close{display:block;}' +
      /* Unread dot - a count would over-promise; the widget only knows that
       * "at least one reply arrived while you were not looking". */
      ".dot{position:absolute;top:2px;right:2px;width:12px;height:12px;border-radius:9999px;" +
      "background:#e5484d;border:2px solid #fff;display:none;}" +
      '.wrap[data-unread="true"] .dot{display:block;}' +
      /* Panel */
      ".panel{box-sizing:border-box;width:min(400px,calc(100vw - " +
      config.offset * 2 +
      "px));height:min(640px,calc(100vh - " +
      (config.offset * 2 + 76) +
      "px));" +
      "border-radius:16px;overflow:hidden;background:#0b0c12;" +
      "box-shadow:0 24px 64px -16px rgba(10,12,18,.5),0 0 0 1px rgba(10,12,18,.12);" +
      "opacity:0;transform:translateY(8px) scale(.98);pointer-events:none;" +
      "transition:opacity .18s ease,transform .18s ease;}" +
      '.wrap[data-open="true"] .panel{opacity:1;transform:none;pointer-events:auto;}' +
      ".panel iframe{display:block;width:100%;height:100%;border:0;background:transparent;}" +
      /* Phones: the corner panel becomes the whole screen. A 400px panel on a
       * 360px viewport is unusable, and the launcher would sit on top of it. */
      "@media (max-width:520px){" +
      ".wrap{bottom:0;left:0;right:0;align-items:flex-end;padding:" +
      config.offset +
      "px;gap:10px;}" +
      '.wrap[data-open="true"]{top:0;padding:0;gap:0;}' +
      '.wrap[data-open="true"] .launcher{display:none;}' +
      '.wrap[data-open="true"] .panel{width:100vw;height:100vh;border-radius:0;}' +
      "}" +
      /* Somebody who has asked their OS to stop animating things means it. */
      "@media (prefers-reduced-motion:reduce){" +
      ".launcher,.panel{transition:none;}" +
      ".launcher:hover{transform:none;}" +
      "}"
    );
  }

  var ICON_OPEN =
    '<svg class="icon-open" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>';

  var ICON_CLOSE =
    '<svg class="icon-close" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';

  function mount() {
    host = document.createElement("div");
    host.setAttribute("id", "vegaduta-widget-root");
    /* The host element itself is a zero-size anchor; everything visible is
     * position:fixed inside the shadow root. Keeping the host out of flow means
     * appending it to <body> can never shift the customer's layout. */
    host.style.cssText = "all:initial;position:static;";
    document.body.appendChild(host);

    /* mode:"open" rather than "closed" on purpose. A closed root would stop the
     * customer from ever inspecting or debugging the launcher in devtools, and
     * buys no real protection - the host page runs the same JS context we do
     * and could reach in regardless. Open is the honest choice. */
    root = host.attachShadow({ mode: "open" });

    var style = document.createElement("style");
    style.textContent = buildStyles();
    root.appendChild(style);

    var wrap = document.createElement("div");
    wrap.className = "wrap";
    wrap.setAttribute("data-open", "false");
    wrap.setAttribute("data-unread", "false");

    panel = document.createElement("div");
    panel.className = "panel";
    /* aria-hidden while closed so a screen reader does not announce a chat
     * transcript that is not visible. Toggled in setOpen(). */
    panel.setAttribute("aria-hidden", "true");

    launcher = document.createElement("button");
    launcher.className = "launcher";
    launcher.setAttribute("type", "button");
    launcher.setAttribute("aria-label", config.launcherLabel);
    launcher.setAttribute("aria-expanded", "false");
    launcher.innerHTML = ICON_OPEN + ICON_CLOSE + '<span class="dot"></span>';
    unreadDot = launcher.querySelector(".dot");
    launcher.addEventListener("click", function () {
      setOpen(!open);
    });

    wrap.appendChild(panel);
    wrap.appendChild(launcher);
    root.appendChild(wrap);

    /* Escape closes, matching every other dismissible surface on the web. The
     * listener is on the HOST document because that is where focus lives while
     * the launcher is focused; the framed document forwards its own Escape as a
     * "close" message instead (a keydown inside a cross-origin iframe is not
     * visible from out here). */
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && open) setOpen(false);
    });

    window.addEventListener("message", onFrameMessage);

    if (config.autoOpen) {
      /* Deferred a tick so an auto-opening widget does not compete with the
       * host page's own first paint. */
      window.setTimeout(function () {
        setOpen(true);
      }, 0);
    }
  }

  /* The iframe is created on FIRST OPEN, not at mount. An embed that nobody
   * clicks then costs the customer's page one small script and zero network
   * requests to our app - which is the difference between a widget a marketing
   * team is willing to put on their homepage and one they are not. */
  function ensureFrame() {
    if (frame || !panel) return;
    frame = document.createElement("iframe");
    frame.setAttribute("title", config.title || "Chat");
    frame.setAttribute("src", buildFrameUrl());
    /* allow-same-origin is required, not optional: without it the framed
     * document gets an opaque origin, which breaks its own localStorage access
     * (the app's ThemeProvider reads it on mount) and would make its calls to
     * api.example.com arrive with Origin: null - rejected by the same
     * first-party CORS allowlist this whole iframe design exists to satisfy.
     * allow-top-navigation is deliberately ABSENT: the framed page renders
     * tenant-authored content (agent name, brand logo URL), and no amount of
     * that content should ever be able to navigate the customer's site away. */
    frame.setAttribute(
      "sandbox",
      "allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
    );
    frame.setAttribute("loading", "lazy");
    panel.appendChild(frame);
  }

  function setOpen(next) {
    /* After destroy() the public API object is still reachable (the customer
     * may hold a reference, and __vegaDutaWidgetLoaded stays set so a second
     * script copy cannot rebuild it) - so every verb must degrade to a warning
     * no-op instead of a TypeError on the nulled-out DOM handles. */
    if (!host || !root || !panel || !launcher) {
      console.warn("[VegaDuta widget] Ignoring call after destroy(); reload the page to re-create the widget.");
      return;
    }
    if (next === open) return;
    open = next;
    if (open) ensureFrame();
    var wrap = root.querySelector(".wrap");
    wrap.setAttribute("data-open", open ? "true" : "false");
    panel.setAttribute("aria-hidden", open ? "false" : "true");
    launcher.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) setUnread(false);
    postToFrame(open ? "opened" : "closed");
  }

  function setUnread(value) {
    var wrap = root && root.querySelector(".wrap");
    if (wrap) wrap.setAttribute("data-unread", value ? "true" : "false");
  }

  function postToFrame(type) {
    if (!frame || !frame.contentWindow || !frameReady) return;
    frame.contentWindow.postMessage({ source: MSG_FROM_HOST, type: type }, WIDGET_ORIGIN);
  }

  /**
   * Inbound messages from the framed chat.
   *
   * Two checks before anything is trusted, and both are load-bearing:
   *   - event.origin must be exactly our widget origin. Any other origin on the
   *     page (an ad frame, another vendor's widget) is discarded.
   *   - event.source must be OUR iframe's window. Origin alone is not enough:
   *     a second frame from the same origin, or the host page itself, could
   *     otherwise drive our launcher.
   */
  function onFrameMessage(event) {
    if (event.origin !== WIDGET_ORIGIN) return;
    if (!frame || event.source !== frame.contentWindow) return;
    var data = event.data;
    if (!data || data.source !== MSG_FROM_FRAME) return;

    switch (data.type) {
      case "ready":
        frameReady = true;
        /* The frame may have finished loading after the user already opened
         * the panel, so replay the current state rather than assuming it is
         * closed. */
        postToFrame(open ? "opened" : "closed");
        break;
      case "close":
        setOpen(false);
        break;
      case "unread":
        if (!open) setUnread(true);
        break;
      default:
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /* Exposed so a customer can drive the widget from their own UI - "Talk to us"
   * links in a nav bar, an exit-intent handler, a post-checkout prompt. Kept to
   * the four verbs that have an obvious meaning; anything richer would be an
   * API we then have to keep stable forever. */
  window.VegaDutaWidget = {
    version: PROTOCOL_VERSION,
    open: function () {
      setOpen(true);
    },
    close: function () {
      setOpen(false);
    },
    toggle: function () {
      setOpen(!open);
    },
    isOpen: function () {
      return open;
    },
    /** Removes the widget entirely - for single-page apps that tear down a
     *  route. Leaves __vegaDutaWidgetLoaded set so a stale second copy of the
     *  script cannot resurrect a half-destroyed instance. */
    destroy: function () {
      window.removeEventListener("message", onFrameMessage);
      if (host && host.parentNode) host.parentNode.removeChild(host);
      host = root = launcher = panel = frame = unreadDot = null;
      open = false;
      frameReady = false;
    },
  };

  // ---------------------------------------------------------------------------
  // Boot
  // ---------------------------------------------------------------------------

  /* A customer who pastes the tag into <head> without defer runs this before
   * <body> exists. Waiting is cheaper than telling them to move the tag. */
  if (document.body) {
    mount();
  } else {
    document.addEventListener("DOMContentLoaded", mount, { once: true });
  }
})();
