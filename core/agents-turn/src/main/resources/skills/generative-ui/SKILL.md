---
name: generative-ui
description: Render an interactive interface instead of plain text - cards, tables, forms, key-value panels, buttons that send a message back to you. Use when a chat answer is better as a small interface (a proposed action to confirm, a digest, a status board, a short form) than as prose. Emit a `json_ui` fenced block; the console renders it safely.
---

# Generative UI

The chat surface can render a small interface from a spec you emit. Use it when
structured interaction beats prose: a comparison table, a status dashboard, a
"confirm this action" card, a form collecting a few answers.

Do NOT use it for ordinary answers, long-form writing, or anything where a
sentence is clearer. One spec per reply.

## The contract

End your reply with exactly one fenced block (the fence language is `json_ui`):

```json_ui
{
  "version": "v1",
  "root": {
    "type": "card",
    "props": {"title": "While you were away"},
    "children": [
      {"type": "text", "props": {"text": "3 jobs ran. 1 needs your attention."}},
      {"type": "button", "props": {"label": "Review proposals"},
       "action": {"type": "sendMessage", "message": "Show me the pending proposals"}}
    ]
  }
}
```

The spec is a typed node tree: `{ "type": ..., "props": {...}, "action": {...},
"children": [...] }`. `version` is `"v1"`. `root` is required.

## Node types and their props

Only these types and props exist. Anything else is stripped by the renderer -
a prop not on this list silently does nothing, so do not invent them.

- Layout: `stack` (gap, align), `row` (gap, align, wrap), `grid` (columns, gap),
  `card` (title, subtitle), `divider`, `spacer` (size)
- Content: `heading` (text, level), `text` (text, muted, markdown),
  `list` (items, ordered), `table` (columns, rows), `keyValue` (entries),
  `badge` (text, tone), `image` (src, alt), `link` (href, text),
  `code` (code, language)
- Interactive: `button` (label, tone), `form` (submitLabel, title),
  `input` (name, label, placeholder, inputType, defaultValue, required),
  `textarea` (name, label, placeholder, defaultValue, rows, required),
  `select` (name, label, options, defaultValue, required),
  `checkbox` (name, label, defaultChecked)
- Live data: `apiCall` (method, path, query, body, title) - the console calls this
  install's own API as the signed-in user and shows the result as a table,
  key-value list or JSON. `path` must start with `/api/`; `{id}` segments become
  inputs. A GET runs by itself; POST/PUT/PATCH/DELETE wait for the user to press
  Send. Find real endpoints with `vegaduta_api_guide` first, never guess a path.

## Interaction: exactly one verb

`button` and `form` nodes may carry:

```json
"action": {"type": "sendMessage", "message": "the message sent back to you"}
```

That is the whole interaction model: the user's click sends `message` to you as
if they typed it. A `form` sends its collected field values along with the
submit. There is no other action type, no event handlers, no script - write the
message as a complete instruction ("Approve proposal 12 for the cost report")
because it arrives as plain user text.

## Rules

- No HTML, markdown-only props, script, or styling escapes. `text` supports
  `markdown: true` for formatted paragraphs; everything else renders literally.
- URLs (`image.src`, `link.href`) must be absolute http(s) - anything else is
  stripped.
- Keep it small: depth <= 20, <= 200 nodes, text <= 8000 chars per prop.
- Lead with prose. Explain the interface in one or two sentences BEFORE the
  block; the rendered card sits under your words.
- The block is removed from the transcript text, so never put your answer's
  only copy of critical information exclusively inside it - summarize in prose
  too.
- Unknown node types render as an explicit "unsupported element" placeholder;
  prefer composing the listed primitives over reaching for a node that does
  not exist.

## Recipes

Proposed action awaiting approval:

```json_ui
{"version": "v1", "root": {"type": "card", "props": {"title": "Proposed action"}, "children": [
  {"type": "text", "props": {"text": "Retry the failed invoice sync for Acme (job 9f2c)."}},
  {"type": "row", "props": {"gap": "sm"}, "children": [
    {"type": "button", "props": {"label": "Approve"}, "action": {"type": "sendMessage", "message": "Approve the pending proposal for job 9f2c"}},
    {"type": "button", "props": {"label": "Dismiss", "tone": "secondary"}, "action": {"type": "sendMessage", "message": "Dismiss the proposal for job 9f2c"}}
  ]}
]}}
```

Status / digest board:

```json_ui
{"version": "v1", "root": {"type": "grid", "props": {"columns": 3}, "children": [
  {"type": "card", "props": {"title": "Runs"}, "children": [{"type": "heading", "props": {"text": "12", "level": 2}}]},
  {"type": "card", "props": {"title": "Failures"}, "children": [{"type": "badge", "props": {"text": "1", "tone": "danger"}}]},
  {"type": "card", "props": {"title": "Pending"}, "children": [{"type": "heading", "props": {"text": "2", "level": 2}}]}
]}}
```
