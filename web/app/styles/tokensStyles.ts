// Tier 1 "paper & ink" token layer (tokens.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "../tokens.css"; // 1-4: fonts, paper surfaces, type roles, pill buttons
import "./tokens/marketing-glass.css"; // 6-8: marketing surface, glass finish, sticky header
import "./tokens/auth-console.css"; // 9-10: auth pages, console glass
import "./tokens/accents.css"; // 11: accent palettes, focus rings
import "./tokens/dark.css"; // 12: dark ink counterpart
import "./tokens/backgrounds-options.css"; // 13-14: backgrounds, motion + contrast options
import "./tokens/brand-menus.css"; // 15-17: brand lockup, menus, consistency pass
import "./tokens/standard.css"; // 18: one standard; perf pass (no card blur)
