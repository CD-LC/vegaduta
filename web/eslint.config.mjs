import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
// typescript-eslint and eslint-plugin-jsx-a11y are declared directly in
// web/package.json devDependencies (typescript-eslint used to resolve only
// as a phantom transitive dep of eslint-config-next; no longer).
import tseslint from "typescript-eslint";
import jsxA11y from "eslint-plugin-jsx-a11y";
import { readFileSync } from "node:fs";

// "next/core-web-vitals" was never accepted via the interactive `next lint`
// first-run prompt in this repo (built entirely through an AI coding
// session, never run locally first) - eslint/eslint-config-next were
// missing from package.json entirely, which is what made `npm run lint`
// fail in CI (see .github/workflows/ci.yml's web job).
//
// Imports eslint-config-next/core-web-vitals directly rather than going
// through @eslint/eslintrc's FlatCompat().extends("next/core-web-vitals")
// shim (this file's first version) - eslint-config-next@16 ships a native
// flat config, and running a native flat config through the legacy
// eslintrc-compat shim's schema validator crashes with "TypeError:
// Converting circular structure to JSON" the first time it needs to format
// a validation error (the shim tries to JSON.stringify a live plugin
// object, e.g. the react plugin, which contains a circular self-reference -
// harmless until the validator's error-formatting path is actually hit).
// Direct import is also just less indirection now that eslint-config-next
// supports it - see https://nextjs.org/docs/pages/api-reference/config/eslint.

// Design-token guard (review finding A13). Colours in app code go through
// the CSS variables declared in app/globals.css (var(--surface-*),
// var(--text-*), var(--accent-*), ...) or the Tailwind utilities built on
// them, so a raw `#rrggbb` string literal is almost always a token that was
// skipped and a surface that will not follow data-mode/data-accent. A local
// inline rule rather than a plugin: it is ~20 lines and the two apps keep
// their configs in lockstep by copy anyway. WARN, not error: the brand
// files below are exempt by design and the remaining literals are being
// worked off in review rather than by a big-bang sweep.
const HEX_LITERAL = /#[0-9a-fA-F]{3,8}\b/;
const noHexLiteralRule = {
  meta: {
    type: "suggestion",
    docs: { description: "Disallow hard-coded hex colours; use the design tokens from app/globals.css." },
    schema: [],
    messages: {
      hex: "Hard-coded colour {{hex}}: use a design token (var(--...) from app/globals.css or its Tailwind utility) instead.",
    },
  },
  create(context) {
    function check(node, text) {
      const match = HEX_LITERAL.exec(text);
      if (match) context.report({ node, messageId: "hex", data: { hex: match[0] } });
    }
    return {
      Literal(node) {
        if (typeof node.value === "string") check(node, node.value);
      },
      TemplateElement(node) {
        check(node, node.value.raw);
      },
    };
  },
};

// Native-control ratchet. The console has its own accessible primitives
// (components/ui/select, checkbox, combobox, confirm-dialog), so a native
// <select> or window.confirm()/prompt() in app code is a regression. Files
// that already violate are listed in components/ui/native-controls-allowlist.txt
// (one path per line, relative to web/): they are exempt until migrated, then
// removed from the list. Anything not listed fails.
const nativeControlsAllowlist = readFileSync(
  new URL("./app/components/ui/native-controls-allowlist.txt", import.meta.url),
  "utf8"
)
  .split("\n")
  .map((l) => l.trim())
  .filter((l) => l && !l.startsWith("#"))
  .map((l) => l.replace(/[[\]]/g, "\\$&")); // paths contain [id] segments; globs need them escaped
const noNativeControlsRule = {
  meta: {
    type: "problem",
    docs: { description: "Disallow native <select> and window.confirm/prompt; use components/ui primitives." },
    schema: [],
    messages: {
      select: "Native <select>: use Select/Combobox from app/components/ui.",
      dialog: "{{name}}(): use ConfirmDialog from app/components/ui/confirm-dialog.",
    },
  },
  create(context) {
    return {
      JSXOpeningElement(node) {
        if (node.name.type === "JSXIdentifier" && node.name.name === "select") {
          context.report({ node, messageId: "select" });
        }
      },
      CallExpression(node) {
        const c = node.callee;
        const names = ["confirm", "prompt"];
        let name = null;
        if (c.type === "Identifier" && names.includes(c.name)) name = c.name;
        else if (
          c.type === "MemberExpression" &&
          c.object.type === "Identifier" &&
          (c.object.name === "window" || c.object.name === "globalThis") &&
          c.property.type === "Identifier" &&
          names.includes(c.property.name)
        )
          name = c.property.name;
        if (name) context.report({ node, messageId: "dialog", data: { name: "window." + name } });
      },
    };
  },
};

const eslintConfig = defineConfig([
  ...nextVitals,
  // e2e/ contains Playwright specs, not app source - don't lint them
  // with the Next.js React/JSX rule set.
  // public/vendor/ holds third-party pre-built bundles (web-llm.js,
  // kokoro.web.js, babel.min.js) copied in verbatim - not our source.
  // web-llm.js carries its own `eslint-disable @typescript-eslint/...`
  // comments, which error here as "Definition for rule ... was not found"
  // because the TS plugin isn't registered for .js files.
  globalIgnores(["e2e/**", "playwright-report/**", "test-results/**", "public/vendor/**", "public/vendored/**"]),

  // TypeScript rules. Until 2026-08 this app linted with core-web-vitals
  // ONLY - zero TS-specific rules - which combined with `"strict": false`
  // in tsconfig.json is how ~820 `: any` / `as any` occurrences
  // accumulated with nobody ever being told to avoid them.
  //
  // eslint-config-next's "next/typescript" block (inside nextVitals above)
  // already wires tseslint's parser+plugin for *.ts/*.tsx, but with no
  // type information and no rules - this block layers rules on top. The
  // plugin registration below is the exact same module instance
  // eslint-config-next registered, so ESLint's "cannot redefine plugin"
  // check is satisfied (identical object reference is allowed).
  {
    files: ["**/*.ts", "**/*.tsx"],
    // Test files and vitest.setup.ts are deliberately NOT ignored here:
    // tsconfig.json stopped excluding them on 2026-08-17, so the project
    // service has a program for them and the promise-safety rules below
    // (no-floating-promises above all) cover the suites too.
    plugins: { "@typescript-eslint": tseslint.plugin },
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        // Rules 1-3 below are type-aware: they need a full TS program, not
        // just an AST. projectService (typescript-eslint v8's successor to
        // `project: "./tsconfig.json"`) builds one per nearest tsconfig.
        // COST: lint goes from a seconds-scale syntax pass to a full
        // typecheck of the app - expect roughly tsc-noEmit-sized wall time
        // (minutes, not seconds, on an app this size) and several hundred
        // MB of extra memory. Accepted deliberately: an async-heavy
        // codebase with zero promise-safety linting is how fire-and-forget
        // bugs ship, and CI is where the cost lands.
        // The .well-known routes are listed in tsconfig.json's include
        // (`**` skips dot-directories). They used to go through
        // allowDefaultProject instead, which built a second, inferred TS
        // program next to the tsconfig one and ran lint out of heap.
        projectService: true,
        // Anchor tsconfig discovery to this directory so lint results
        // don't depend on the invoking process's cwd (CI runs from repo
        // root, `npm run lint` runs from web/).
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // Highest-value rule here: an unawaited promise swallows its
      // rejection silently. In a codebase this async-heavy that is a
      // real-bug detector, not style.
      "@typescript-eslint/no-floating-promises": "error",
      // Catches promises passed where a void-return is expected
      // (forEach callbacks, setTimeout, conditionals testing a Promise
      // object - always truthy). checksVoidReturn.attributes is off
      // because `onClick={async () => ...}` is idiomatic React and React
      // intentionally ignores the handler's return value - flagging it
      // would bury the signal under hundreds of false positives.
      "@typescript-eslint/no-misused-promises": ["error", { checksVoidReturn: { attributes: false } }],
      // `await nonThenable` is dead code at best, a masked missing-call
      // (`await fn` instead of `await fn()`) at worst.
      "@typescript-eslint/await-thenable": "error",
      // WARN, deliberately not error: ~820 existing occurrences (counted
      // 2026-08-17). Erroring would make every lint run fail on legacy
      // code and train people to ignore the linter entirely. Warn makes
      // each `any` visible in review on the lines being touched, which is
      // how the count ratchets down without a big-bang cleanup.
      "@typescript-eslint/no-explicit-any": "warn",
      // Type-only imports are erased at compile time; marking them keeps
      // bundlers/isolatedModules from retaining modules imported purely
      // for their types. Warn + auto-fixable, so it self-heals via
      // `eslint --fix` rather than blocking anyone.
      "@typescript-eslint/consistent-type-imports": [
        "warn",
        { prefer: "type-imports", fixStyle: "inline-type-imports" },
      ],
    },
  },

  // Accessibility. eslint-config-next registers the jsx-a11y plugin but
  // enables only 6 of its rules; this turns on the full recommended set.
  // Only the rules are added (not the plugin) so there is no plugin
  // redefinition conflict with nextVitals.
  {
    files: ["**/*.{jsx,tsx}"],
    rules: {
      ...jsxA11y.flatConfigs.recommended.rules,
      // Our checkbox/radio rows nest the label text two spans deep
      // (label > span > span > text); the default depth of 2 misses it.
      "jsx-a11y/label-has-associated-control": [
        "error",
        {
          depth: 3,
          // The app's own form primitives render the real control internally
          // (Input/Textarea -> <input>/<textarea>, SwitchToggle -> role="switch",
          // Select -> its trigger button). A <label> nesting one IS associated;
          // the rule only learns that by name via controlComponents.
          controlComponents: ["Input", "Textarea", "Select", "SwitchToggle"],
        },
      ],
      // Warn, not error, until the remaining sites are worked through
      // (FE-040 follow-up; counts as of 2026-09-30):
      // - no-autofocus (18): mostly inputs in just-opened dialogs/forms;
      //   each needs a per-site focus-management decision.
      // - media-has-caption (11): AI-generated / live-call audio and video
      //   with no caption track to attach yet.
      // - no-noninteractive-tabindex (5): focusable tooltip triggers
      //   (Radix TooltipTrigger asChild) so tooltips open on keyboard focus.
      "jsx-a11y/no-autofocus": "warn",
      "jsx-a11y/media-has-caption": "warn",
      "jsx-a11y/no-noninteractive-tabindex": "warn",
    },
  },

  // Runtime public config guard. Next.js inlines `process.env.NEXT_PUBLIC_*`
  // at BUILD time, so a direct read silently ignores the container's
  // runtime env (window.__ENV). All public config goes through
  // getPublicEnv() in app/lib/env.ts - the only file allowed the literal
  // reads (see web/docs/RUNTIME-CONFIG.md). Tests may set process.env.
  {
    files: ["app/**/*.ts", "app/**/*.tsx", "app/**/*.js", "app/**/*.jsx"],
    ignores: ["app/lib/env.ts", "**/*.test.ts", "**/*.test.tsx"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "MemberExpression[object.type='MemberExpression'][object.object.name='process'][object.property.name='env'][property.name=/^NEXT_PUBLIC_/]",
          message:
            "Read public config via getPublicEnv() from app/lib/env.ts (runtime > build-time), not process.env.NEXT_PUBLIC_*.",
        },
      ],
    },
  },

  // Design tokens (A13): warn on hex colour literals in components and
  // pages. Brand assets are the one place a fixed colour is correct - the
  // logo marks, the channel brand palette (WhatsApp green, Discord blurple,
  // ...) in BrandIcons.tsx and the BrandIdentityModal scene, which
  // reproduces uploaded brand artwork pixel-for-pixel and must not follow
  // the theme.
  {
    files: ["app/components/**/*.tsx", "app/**/page.tsx"],
    ignores: ["app/components/**/*Brand*.tsx", "**/*.test.tsx"],
    plugins: { "design-tokens": { rules: { "no-hex-literal": noHexLiteralRule } } },
    rules: {
      "design-tokens/no-hex-literal": "warn",
    },
  },

  // Native controls (see noNativeControlsRule above). ui/ primitives are the
  // implementation and tests may stub dialogs; allowlisted files are the
  // ratchet debt.
  {
    files: ["app/**/*.ts", "app/**/*.tsx"],
    ignores: ["app/components/ui/**", "**/*.test.ts", "**/*.test.tsx", ...nativeControlsAllowlist],
    plugins: { "native-controls": { rules: { "no-native-controls": noNativeControlsRule } } },
    rules: {
      "native-controls/no-native-controls": "error",
    },
  },
]);

export default eslintConfig;
