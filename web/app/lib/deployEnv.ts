// NEXT_PUBLIC_DEPLOY_ENV does two jobs. Its tier values ("personal", "lite",
// "full") pick how the console behaves (see personalInstall.ts); anything else
// ("production", "staging") is the label of the environment pill in the top
// bar. A tier is not an environment to warn about, so it gets no pill (a lite
// install used to show an amber LITE badge that read like staging).

const TIER_VALUES = new Set(["personal", "lite", "full"]);

/** True when `env` names an environment worth a top-bar pill: set, and not an install tier. */
export function showEnvironmentPill(env: string | null | undefined): boolean {
  const value = (env ?? "").trim().toLowerCase();
  return value !== "" && !TIER_VALUES.has(value);
}
