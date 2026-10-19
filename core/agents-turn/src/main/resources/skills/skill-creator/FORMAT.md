# Skill format reference

## Folder layout

```
my-skill/
  SKILL.md            required - frontmatter + instructions
  REFERENCE.md        optional - extra guidance, loaded only when SKILL.md points to it
  templates/brief.md  optional - any text resource
  scripts/check.py    optional - runnable script
```

Upload as a `.zip` of the folder (or with `SKILL.md` at the archive root).

## Frontmatter rules

| Field | Rules |
|---|---|
| `name` | Required. Max 64 characters. Lowercase letters, digits and hyphens only. No XML/HTML tags. Must not contain the reserved words `anthropic`, `claude` or `vegaduta`. Must be unique in the workspace and must not match a built-in skill. Cannot be changed after creation. |
| `description` | Required, non-empty. Max 1024 characters. No XML/HTML tags. Must say what the skill does AND when to use it. |

If the description contains a colon, wrap it in double quotes:
`description: "Drafts refund replies. Use when: a customer asks for money back."`

## Size limits

- `SKILL.md` instructions: 100,000 characters (aim for far less - under ~5,000 tokens)
- Bundled files: 100 files, 256 KB each, 2 MB unpacked in total, 5 MB zip
- Text files only (UTF-8). Binary files such as images are skipped on import.
- 50 custom skills per workspace

## The three loading levels

| Level | What | When it enters the agent's context |
|---|---|---|
| 1 | `name` + `description` | Always - listed in every agent's instructions (~100 tokens) |
| 2 | `SKILL.md` body | When the agent decides the request matches and calls `load_skill` |
| 3 | Bundled files | Only when the instructions send the agent to one (`read_skill_file`), or a script is run (`run_skill_script` - only its output enters context) |

## Script contract

Scripts run in the workspace's isolated code sandbox: no network access, no package
installation, hard time and memory limits, and no access to workspace secrets.

```python
def run(input):
    # input is the JSON object the agent passed
    rows = input.get("rows", [])
    return {"count": len(rows)}   # must be JSON-serialisable
```

If the sandbox is not enabled for the deployment, the agent is not offered
`run_skill_script`; design the skill so its instructions still work without the script.

## Scope

- Workspace admins create, edit, enable/disable and delete skills.
- Every enabled skill is available to every agent by default. Per agent, skills can be turned
  off entirely or restricted to a chosen list (agent settings -> Skills).
- Built-in skills can be disabled per workspace but not edited. To customise one, export it,
  rename it, and upload it as a custom skill.
