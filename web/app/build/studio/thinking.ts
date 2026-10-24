/** Local models (qwen, deepseek-r1 style) wrap their reasoning in
 * <think>…</think>. The stream box shows it raw, which reads like garbage to
 * a user mid-turn; split it out so the UI can render the reasoning dimmed and
 * the visible reply clean. A think block still open at the end of the string
 * counts as streaming reasoning, not visible text. */
export function splitThink(text: string): { thinking: string; visible: string } {
  const open = /<think>/i.exec(text);
  if (!open) return { thinking: "", visible: text };
  const close = /<\/think>/i.exec(text.slice(open.index + open[0].length));
  const head = text.slice(0, open.index);
  if (!close) return { thinking: text.slice(open.index + open[0].length), visible: head };
  const mid = close.index + open.index + open[0].length;
  const tail = text.slice(mid + "</think>".length);
  const rest = splitThink(head + tail);
  return { thinking: (text.slice(open.index + open[0].length, mid) + rest.thinking).trim(), visible: rest.visible };
}
