/** The pulsing status dot ("on air", "watching", "analyzing"), coloured by a --state-* token. */
export default function LiveDot({ state }: { state: "--state-error" | "--state-running" }) {
  return (
    <span
      className="state-dot state-dot-live"
      style={{ ["--state" as any]: `var(${state})` } as any}
      aria-hidden="true"
    />
  );
}
