// Tailwind CSS v4 - single PostCSS plugin, no separate autoprefixer needed
// (v4 handles vendor prefixing internally). No tailwind.config.js either:
// v4's default is CSS-first configuration via the `@theme` block in
// globals.css, plus automatic content detection across the project - no
// content: [] glob list to keep in sync by hand as new components are added.
const config = {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};

export default config;
