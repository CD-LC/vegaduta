"""Store-ready mobile output for the capacitor-app template.

Owns three things:

1. VALIDATION of the store listing fields against Google Play's limits
   (title 30, short description 80, full description 4000, reverse-DNS
   package id, semantic-ish version name).
2. RENDERING the store metadata file set (fastlane android + iOS metadata,
   privacy-policy stub, STORE-CHECKLIST.md, store/store-config.json, and the
   capacitor.config.json / package.json edits) as plain [{path, content}]
   that is committed through the EXISTING apply pipeline
   (generate.apply_files_to_project) — no new write path.
3. STATUS: the project's file set + parsed config, from which the web
   client derives the readiness checklist.

Publishing to Google Play / the App Store is human-only. Nothing here
collects a store credential or uploads a build; the generated CI job signs
with GitHub Secrets the user adds themselves and only attaches the AAB to
the workflow run.
"""

from __future__ import annotations

import json
import re

from . import generate, projects
from .generate import GenerateError
from .settings import SETTINGS, Settings
from .tenancy import TenantContext

TEMPLATE_ID = "capacitor-app"

# Google Play listing limits.
MAX_TITLE = 30
MAX_SHORT = 80
MAX_FULL = 4000
MAX_PACKAGE_ID = 155
MAX_CHANGELOG = 500
IOS_MAX_SUBTITLE = 30

DEFAULT_PACKAGE_ID = "app.builder.generated"
DEFAULT_APP_NAME = "My App"
DEFAULT_VERSION = "0.0.1"

STORE_CONFIG_PATH = "store/store-config.json"
ANDROID_META = "fastlane/metadata/android/en-US"
IOS_META = "fastlane/metadata/ios/en-US"

_PACKAGE_RX = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
_VERSION_RX = re.compile(r"^\d{1,4}\.\d{1,4}\.\d{1,4}(-[0-9A-Za-z.]{1,20})?$")
# Segments Java/Kotlin (and therefore Android) reject as identifiers.
_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false", "null",
}


def validate_store_config(cfg: dict) -> dict:
    """Return the normalized config or raise GenerateError (HTTP 422) naming
    the first offending field."""
    def text(key: str, max_len: int, multiline: bool = False) -> str:
        v = cfg.get(key)
        if not isinstance(v, str):
            raise GenerateError(f"{key} is required")
        v = v.replace("\r\n", "\n").strip()
        if not v:
            raise GenerateError(f"{key} must not be empty")
        if len(v) > max_len:
            raise GenerateError(
                f"{key} is {len(v)} characters; Google Play allows at most "
                f"{max_len}")
        if not multiline and "\n" in v:
            raise GenerateError(f"{key} must be a single line")
        return v

    app_name = text("app_name", MAX_TITLE)
    short = text("short_description", MAX_SHORT)
    full = text("full_description", MAX_FULL, multiline=True)

    package_id = (cfg.get("package_id") or "").strip() \
        if isinstance(cfg.get("package_id"), str) else ""
    if not package_id:
        raise GenerateError("package_id is required")
    if len(package_id) > MAX_PACKAGE_ID or not _PACKAGE_RX.match(package_id):
        raise GenerateError(
            "package_id must be reverse-DNS, e.g. com.acme.myapp: lowercase "
            "letters, digits and underscores, at least two dot-separated "
            "segments, each starting with a letter")
    bad = [s for s in package_id.split(".") if s in _RESERVED]
    if bad:
        raise GenerateError(
            f"package_id segment {bad[0]!r} is a reserved Java keyword")

    version = (cfg.get("version_name") or "").strip() \
        if isinstance(cfg.get("version_name"), str) else ""
    if not _VERSION_RX.match(version):
        raise GenerateError(
            "version_name must look like 1.0.0 (optionally 1.0.0-beta.1)")

    return {"app_name": app_name, "package_id": package_id,
            "version_name": version, "short_description": short,
            "full_description": full}


def default_config() -> dict:
    return {
        "app_name": DEFAULT_APP_NAME,
        "package_id": DEFAULT_PACKAGE_ID,
        "version_name": DEFAULT_VERSION,
        "short_description": "A mobile app built with VegaDuta App Builder.",
        "full_description": (
            "Describe what your app does, who it is for, and what makes it "
            "worth installing. Replace this text before you submit to the "
            "store."),
    }


def _subtitle(short: str) -> str:
    if len(short) <= IOS_MAX_SUBTITLE:
        return short
    cut = short[:IOS_MAX_SUBTITLE].rsplit(" ", 1)[0].rstrip(",.;:- ")
    return cut or short[:IOS_MAX_SUBTITLE]


def _nl(s: str) -> str:
    return s.rstrip("\n") + "\n"


def _release_notes(version: str) -> str:
    return _nl(f"Version {version}: bug fixes and improvements.")


def render_metadata_files(cfg: dict) -> list[dict]:
    """The store metadata file set for an (already validated) config."""
    c = cfg
    files = {
        f"{ANDROID_META}/title.txt": _nl(c["app_name"]),
        f"{ANDROID_META}/short_description.txt": _nl(c["short_description"]),
        f"{ANDROID_META}/full_description.txt": _nl(c["full_description"]),
        # `default.txt` is the changelog fastlane supply falls back to when
        # there is no <versionCode>.txt for the uploaded build.
        f"{ANDROID_META}/changelogs/default.txt":
            _release_notes(c["version_name"])[:MAX_CHANGELOG],
        f"{IOS_META}/name.txt": _nl(c["app_name"]),
        f"{IOS_META}/subtitle.txt": _nl(_subtitle(c["short_description"])),
        f"{IOS_META}/description.txt": _nl(c["full_description"]),
        f"{IOS_META}/keywords.txt": _nl(
            ", ".join(w for w in re.split(r"[^A-Za-z0-9]+", c["app_name"])
                      if len(w) > 2).lower() or "app"),
        f"{IOS_META}/release_notes.txt": _release_notes(c["version_name"]),
        "PRIVACY-POLICY.md": render_privacy_policy(c),
        "STORE-CHECKLIST.md": render_checklist(c),
        STORE_CONFIG_PATH: json.dumps(c, indent=2, ensure_ascii=False) + "\n",
    }
    return [{"path": p, "content": files[p]} for p in sorted(files)]


def render_privacy_policy(c: dict) -> str:
    return (
        f"# Privacy Policy - {c['app_name']}\n\n"
        "> STUB. This is a starting point, not legal advice. Google Play and "
        "the App Store both require a privacy policy hosted at a public URL. "
        "Review and complete every section, then host this page (GitHub Pages "
        "works) and paste its URL into both store consoles.\n\n"
        "## What data the app collects\n\n"
        "_List every kind of data (account info, analytics, crash logs, "
        "location, photos). If the app collects nothing, say so plainly._\n\n"
        "## How the data is used and shared\n\n"
        "_Name each purpose and every third party that receives data._\n\n"
        "## Retention and deletion\n\n"
        "_Say how long data is kept and how a user can ask for deletion._\n\n"
        "## Contact\n\n"
        "_An email address users can reach._\n")


def render_checklist(c: dict) -> str:
    name, pkg = c["app_name"], c["package_id"]
    return f"""# Store checklist - {name}

Package / bundle id: `{pkg}`  |  Version: `{c['version_name']}`

Publishing is a human step: you upload the build and submit the listing in
Google Play Console / App Store Connect yourself. Nothing in this repo has
your store credentials.

## 1. Identity
- [ ] Package id `{pkg}` is final (it can never change after first publish)
- [ ] App name and descriptions reviewed: `fastlane/metadata/`

## 2. Icons and splash
- [ ] Replace `assets/icon.png` with your real 1024x1024 PNG (no transparency
      for iOS). CI derives every Android/iOS icon size from this one file
      with `@capacitor/assets` (`npm run assets`).
- [ ] Play listing icon 512x512 and feature graphic 1024x500 uploaded in the console

## 3. Screenshots
- [ ] Android: at least 2 phone screenshots (Play accepts 320-3840 px sides)
- [ ] iOS: screenshots for the required device sizes (6.9-inch iPhone at minimum)

## 4. Android release build (signed AAB)
Add these GitHub repository secrets (Settings > Secrets and variables > Actions).
Without them the `android-release` job skips itself with a notice - the rest of
CI still passes.
- [ ] `ANDROID_KEYSTORE_BASE64` - `base64 -w0 upload-keystore.jks`
- [ ] `ANDROID_KEYSTORE_PASSWORD`
- [ ] `ANDROID_KEY_ALIAS`
- [ ] `ANDROID_KEY_PASSWORD`
- [ ] Enrol in Play App Signing so this is only the upload key
- [ ] Keep the keystore backed up outside GitHub; losing it blocks updates
- [ ] Target API 36 and 16 KB page-size settings are applied by the job
      (`scripts/android-release-config.mjs`); confirm in Play Console pre-launch report

## 5. Play Console declarations
- [ ] Data safety form matches `PRIVACY-POLICY.md` and what the app really does
- [ ] Privacy policy URL set (host `PRIVACY-POLICY.md`)
- [ ] Content rating questionnaire, target audience, ads declaration
- [ ] Accessibility: do NOT request the AccessibilityService API unless the app
      is a genuine accessibility tool; Play rejects other uses. This template
      does not need it.

## 6. iOS
- [ ] Apple Developer Program membership and an App Store Connect app record
- [ ] Signing certificates and provisioning profiles (the CI iOS job is an
      unsigned simulator build only)
- [ ] App privacy "nutrition label" and privacy policy URL
- [ ] `fastlane/metadata/ios/en-US/` reviewed (keywords, subtitle, notes)
"""


def _read_json(text: str | None) -> dict:
    if not text:
        return {}
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except ValueError:
        return {}


def render_config_edits(cfg: dict, capacitor_json: str | None,
                        package_json: str | None) -> list[dict]:
    """capacitor.config.json (appId/appName) and package.json (version)
    edits, preserving every other key the user or model added."""
    cap = _read_json(capacitor_json) or {"webDir": "dist"}
    cap["appId"] = cfg["package_id"]
    cap["appName"] = cfg["app_name"]
    out = [{"path": "capacitor.config.json",
            "content": json.dumps(cap, indent=2, ensure_ascii=False) + "\n"}]
    pkg = _read_json(package_json)
    if pkg:
        pkg["version"] = cfg["version_name"].split("-")[0]
        out.append({"path": "package.json",
                    "content": json.dumps(pkg, indent=2, ensure_ascii=False)
                    + "\n"})
    return out


def _require_capacitor(project) -> None:
    if project.template_id != TEMPLATE_ID:
        raise GenerateError(
            "store-config is only available for mobile (capacitor-app) "
            "projects")


def apply_store_config(ctx: TenantContext, project_id: str, raw: dict,
                       settings: Settings = SETTINGS) -> dict:
    """Validate, render and commit the store metadata via the same apply
    pipeline generated code uses."""
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    _require_capacitor(project)
    cfg = validate_store_config(raw)
    read, _list = generate._project_reader(ctx, project, settings)
    files = render_metadata_files(cfg) + render_config_edits(
        cfg, read("capacitor.config.json"), read("package.json"))
    result = generate.apply_files_to_project(
        ctx, project_id, f"Store listing: {cfg['app_name']} {cfg['version_name']}",
        files, settings)
    result["store_config"] = cfg
    return result


def store_status(ctx: TenantContext, project_id: str,
                 settings: Settings = SETTINGS) -> dict:
    """{paths, config, capacitor} - everything the web checklist needs.
    Best-effort reads: an unreadable repo yields empty paths, not a 500."""
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    _require_capacitor(project)
    read, list_paths = generate._project_reader(ctx, project, settings)
    stored = _read_json(read(STORE_CONFIG_PATH))
    cap = _read_json(read("capacitor.config.json"))
    config = default_config()
    config.update({k: v for k, v in stored.items() if k in config})
    config["package_id"] = cap.get("appId") or config["package_id"]
    config["app_name"] = cap.get("appName") or config["app_name"]
    return {"paths": sorted(list_paths()), "config": config,
            "has_store_config": bool(stored),
            "limits": {"title": MAX_TITLE, "short": MAX_SHORT,
                       "full": MAX_FULL}}
