"""HTTP route extraction for the api-surface endpoint.

Deterministic regex pass per framework family - same "an edge/route exists
only if a fixed pattern produced it" contract as graph_extract. Covers the
frameworks agents actually meet:

- Python:  FastAPI/Starlette/Flask/Django decorators (`@app.get`, `@router.post`,
           `@bp.route`, `path("...", view)`)
- JS/TS:   Express/Koa/Hono (`app.get("/x", ...)`), Nest decorators
           (`@Get("/x")`)
- Java:    Spring MVC annotations (`@GetMapping`, `@RequestMapping(method=...)`),
           JAX-RS (`@GET`/`@Path`)
- Go:      net/http `http.HandleFunc`, Gin/Echo `router.GET`, chi `r.Get`
- Rust:    axum `.route("/x", get(h))`

One route row per (method, path, handler-ish context, line). Anything the
patterns can't pin down honestly (computed paths, dynamic routers) is simply
not reported - absence never claims "no routes", see the endpoint's
`coverage` field.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class Route:
    path: str          # file the route is defined in
    line: int          # 1-based
    method: str        # GET|POST|... |ANY|VIEW (framework-level, e.g. Flask route())
    route: str         # the URL pattern as written
    handler: str       # best-effort handler/context name


# (language, compiled (regex, method_group|None, route_group, handler_group|None))
_PATTERNS: dict[str, list[tuple[re.Pattern, int | None, int, int | None]]] = {
    "python": [
        # FastAPI/Starlette/Flask: @app.get("/x") / @router.post("/x") / @bp.route
        (re.compile(r"@\w+\.(get|post|put|delete|patch|head|options|route)\("
                    r"\s*['\"]([^'\"]+)['\"]"), 1, 2, None),
        # Django urls.py: path("x", view) / re_path("x", view)
        (re.compile(r"\b(?:re_)?path\(\s*['\"]([^'\"]+)['\"]\s*,"
                    r"\s*([\w.]+)"), None, 1, 2),
    ],
    "javascript": [
        (re.compile(r"\b(?:app|router|server|routes|route)\."
                    r"(get|post|put|delete|patch|use|all)\("
                    r"\s*['\"`]([^'\"`]+)['\"`]"), 1, 2, None),
        (re.compile(r"@(?:Get|Post|Put|Delete|Patch|All|Controller)\("
                    r"\s*['\"]([^'\"]+)['\"]"), None, 1, None),
    ],
    "typescript": [],
    "tsx": [],
    "java": [
        # Spring: @GetMapping("/x"), @RequestMapping(value="/x", method=GET)
        (re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\("
                    r"\s*(?:value\s*=\s*)?['\"]([^'\"]+)['\"]"), 1, 2, None),
        # JAX-RS @Path("/x") on its own line; method comes from @GET etc.
        (re.compile(r"@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b\s*"
                    r"(?:@Path\(\s*['\"]([^'\"]+)['\"]\s*\))?"), 1, 2, None),
    ],
    "kotlin": [],
    "go": [
        (re.compile(r"\bhttp\.HandleFunc\(\s*\"([^\"]+)\"\s*,\s*([\w.]+)"),
         None, 1, 2),
        (re.compile(r"\b(?:r|router|e|app|mux|group)\."
                    r"(GET|POST|PUT|DELETE|PATCH|Any|Handle|Get|Post|Put|Delete|"
                    r"Patch)\(\s*\"([^\"]+)\""), 1, 2, None),
    ],
    "rust": [
        (re.compile(r"\.route\(\s*\"([^\"]+)\"\s*,\s*"
                    r"(get|post|put|delete|patch|head|options|any)\s*\("
                    r"\s*([\w:]+)?"), 2, 1, 3),
    ],
    "csharp": [
        (re.compile(r"\[(?:HttpGet|HttpPost|HttpPut|HttpDelete|HttpPatch|Route)"
                    r"(?:\(\s*\"([^\"]+)\"\s*)?\]"), None, 1, None),
    ],
}
# Languages that share the JS/TS patterns.
_PATTERNS["typescript"] = _PATTERNS["javascript"] + [
    (re.compile(r"@(?:Get|Post|Put|Delete|Patch|All|Controller)\("
                r"\s*['\"]([^'\"]+)['\"]"), None, 1, None),
]
_PATTERNS["tsx"] = _PATTERNS["typescript"]
_PATTERNS["kotlin"] = _PATTERNS["java"]

_METHOD_FIX = {
    "get": "GET", "post": "POST", "put": "PUT", "delete": "DELETE",
    "patch": "PATCH", "head": "HEAD", "options": "OPTIONS",
    "route": "ANY", "use": "ANY", "all": "ANY", "any": "ANY",
    "handle": "ANY", "request": "ANY",
}


def extract_routes(path: str, text: str, language: str) -> list[Route]:
    """Deterministic route rows for one source file; empty for languages or
    files with no framework-shaped lines."""
    pats = _PATTERNS.get(language)
    if not pats:
        return []
    out: list[Route] = []
    for i, line in enumerate(text.split("\n"), 1):
        if len(line) > 500:
            continue
        for rx, m_g, r_g, h_g in pats:
            m = rx.search(line)
            if not m:
                continue
            raw_method = m.group(m_g) if m_g else None
            method = (_METHOD_FIX.get(raw_method.lower(), "ANY")
                      if raw_method else "VIEW")
            route = m.group(r_g) or ""
            handler = m.group(h_g) if h_g else ""
            if route and not route.startswith("/"):
                route = "/" + route.lstrip("^")
            out.append(Route(path=path, line=i, method=method,
                             route=route, handler=handler or ""))
            break  # first matching pattern per line wins
    return out
