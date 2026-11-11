"""Dependency manifest inventory for the offline security scan.

Parses requirements*.txt, package.json, package-lock.json, pom.xml and go.mod
into one normalized record shape. This is an INVENTORY ONLY: no vulnerability
database is consulted (that needs an advisory feed, which this offline service
deliberately does not carry). `AdvisoryProvider` is the extension hook.

Record: {ecosystem, name, version, spec, scope, direct, file, line, purl}
  ecosystem  purl type: pypi | npm | maven | golang
  version    exact resolved/pinned version, or "" when only a range is known
  spec       the declared requirement text (e.g. ">=2.0,<3")
  scope      runtime | dev | optional | peer | test | provided | indirect
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from collections.abc import Callable

# Hook: receives the normalized dependency list, returns advisory dicts
# ({"purl", "id", "severity", "summary", ...}). Left None by default.
AdvisoryProvider = Callable[[list], list]

MANIFEST_NAMES = ("package.json", "package-lock.json", "pom.xml", "go.mod")
_REQ_NAME_RX = re.compile(r"^requirements([-_.][\w.\-]*)?\.txt$", re.IGNORECASE)


def is_manifest(basename: str) -> bool:
    return basename in MANIFEST_NAMES or bool(_REQ_NAME_RX.match(basename))


def _purl(eco: str, name: str, version: str) -> str:
    if eco == "maven":
        base = "pkg:maven/" + name.replace(":", "/", 1)
    elif eco == "npm" and name.startswith("@"):
        base = "pkg:npm/" + name.replace("@", "%40", 1)
    else:
        base = f"pkg:{eco}/{name}"
    return f"{base}@{version}" if version else base


def _rec(eco, name, version, spec, scope, direct, file, line=0) -> dict:
    return {
        "ecosystem": eco,
        "name": name,
        "version": version,
        "spec": spec,
        "scope": scope,
        "direct": direct,
        "file": file,
        "line": line,
        "purl": _purl(eco, name, version),
    }


# ---------------------------------------------------------------------------
# requirements*.txt
# ---------------------------------------------------------------------------

_REQ_RX = re.compile(
    r"^([A-Za-z0-9][A-Za-z0-9._\-]*)\s*(?:\[[^\]]*\])?\s*((?:[<>=!~]=?|===)[^;#]*)?"
)


def parse_requirements(text: str, rel: str) -> list[dict]:
    out = []
    for i, raw in enumerate(text.split("\n"), 1):
        line = raw.split(" #")[0].strip()
        if not line or line.startswith(("#", "-", "git+", "http://", "https://", ".", "/")):
            continue
        m = _REQ_RX.match(line)
        if not m:
            continue
        spec = (m.group(2) or "").strip().rstrip("\\").strip()
        pin = re.fullmatch(r"===?\s*([A-Za-z0-9.!+\-_*]+)", spec)
        version = pin.group(1) if pin and "*" not in pin.group(1) else ""
        name = re.sub(r"[-_.]+", "-", m.group(1)).lower()
        out.append(_rec("pypi", name, version, spec, "runtime", True, rel, i))
    return out


# ---------------------------------------------------------------------------
# npm
# ---------------------------------------------------------------------------

_NPM_SCOPES = {
    "dependencies": "runtime",
    "devDependencies": "dev",
    "optionalDependencies": "optional",
    "peerDependencies": "peer",
}


def _exact(spec: str) -> str:
    return spec if re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][\w.\-+]+)?", spec) else ""


def parse_package_json(text: str, rel: str) -> list[dict]:
    try:
        data = json.loads(text)
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    out = []
    for key, scope in _NPM_SCOPES.items():
        deps = data.get(key)
        if not isinstance(deps, dict):
            continue
        for name, spec in deps.items():
            spec = spec if isinstance(spec, str) else ""
            out.append(_rec("npm", name, _exact(spec), spec, scope, True, rel))
    return out


def parse_package_lock(text: str, rel: str) -> list[dict]:
    try:
        data = json.loads(text)
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    out: list[dict] = []
    pkgs = data.get("packages")
    if isinstance(pkgs, dict):  # lockfile v2/v3
        root = pkgs.get("", {}) if isinstance(pkgs.get(""), dict) else {}
        direct_names = set()
        for k in _NPM_SCOPES:
            if isinstance(root.get(k), dict):
                direct_names.update(root[k])
        for path, meta in pkgs.items():
            if not path or not isinstance(meta, dict) or "node_modules/" not in path:
                continue
            name = meta.get("name") or path.rsplit("node_modules/", 1)[1]
            scope = "dev" if meta.get("dev") else "optional" if meta.get("optional") else "runtime"
            direct = path.count("node_modules/") == 1 and name in direct_names
            if not direct and scope == "runtime":
                scope = "indirect"
            out.append(_rec("npm", name, str(meta.get("version", "")), "", scope, direct, rel))
        return out

    def walk(deps: dict, top: bool) -> None:  # lockfile v1
        for name, meta in deps.items():
            if not isinstance(meta, dict):
                continue
            scope = "dev" if meta.get("dev") else "runtime"
            out.append(_rec("npm", name, str(meta.get("version", "")), "", scope, top, rel))
            if isinstance(meta.get("dependencies"), dict):
                walk(meta["dependencies"], False)

    if isinstance(data.get("dependencies"), dict):
        walk(data["dependencies"], True)
    return out


# ---------------------------------------------------------------------------
# Maven
# ---------------------------------------------------------------------------


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_pom(text: str, rel: str) -> list[dict]:
    # No DTD/entities in a legitimate pom; refuse them rather than rely on the
    # parser's expansion limits (entity-expansion DoS on tenant input).
    if "<!DOCTYPE" in text or "<!ENTITY" in text:
        return []
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return []
    props: dict[str, str] = {}
    for el in root:
        if _local(el.tag) == "properties":
            for p in el:
                props[_local(p.tag)] = (p.text or "").strip()
        elif _local(el.tag) in ("version", "groupId") and el.text:
            props["project." + _local(el.tag)] = el.text.strip()

    def sub(v: str) -> str:
        return re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), v)

    def child(el, name: str) -> str:
        for c in el:
            if _local(c.tag) == name:
                return (c.text or "").strip()
        return ""

    out: list[dict] = []

    def visit(el, managed: bool) -> None:
        for c in el:
            t = _local(c.tag)
            if t == "dependencyManagement":
                visit(c, True)
            elif t == "dependency" and not managed:
                g, a = sub(child(c, "groupId")), sub(child(c, "artifactId"))
                if not g or not a:
                    continue
                v = sub(child(c, "version"))
                scope = child(c, "scope") or "runtime"
                spec = v
                ver = "" if (not v or "${" in v or v.startswith(("[", "("))) else v
                out.append(_rec("maven", f"{g}:{a}", ver, spec, scope, True, rel))
            else:
                visit(c, managed)

    visit(root, False)
    return out


# ---------------------------------------------------------------------------
# go.mod
# ---------------------------------------------------------------------------

_GO_REQ_RX = re.compile(r"^\s*([^\s]+)\s+(v[^\s]+)(\s*//\s*indirect)?")


def parse_go_mod(text: str, rel: str) -> list[dict]:
    out = []
    in_block = False
    for i, raw in enumerate(text.split("\n"), 1):
        line = raw.strip()
        if in_block:
            if line.startswith(")"):
                in_block = False
                continue
            body = line
        elif line.startswith("require ("):
            in_block = True
            continue
        elif line.startswith("require "):
            body = line[len("require ") :]
        else:
            continue
        m = _GO_REQ_RX.match(body)
        if m:
            indirect = bool(m.group(3))
            out.append(
                _rec(
                    "golang",
                    m.group(1),
                    m.group(2),
                    m.group(2),
                    "indirect" if indirect else "runtime",
                    not indirect,
                    rel,
                    i,
                )
            )
    return out


def parse_manifest(basename: str, text: str, rel: str) -> list[dict]:
    if basename == "package.json":
        return parse_package_json(text, rel)
    if basename == "package-lock.json":
        return parse_package_lock(text, rel)
    if basename == "pom.xml":
        return parse_pom(text, rel)
    if basename == "go.mod":
        return parse_go_mod(text, rel)
    if _REQ_NAME_RX.match(basename):
        return parse_requirements(text, rel)
    return []
