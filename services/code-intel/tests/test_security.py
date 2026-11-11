"""Framework-free tests for codebase_rag/security.py (+ security_rules.py,
security_deps.py): secret detection and redaction, dangerous-sink rules with
false-positive guards, dependency inventory, suppression, test-path skipping,
symlink confinement and per-tenant isolation through the service layer.

Credential-shaped fixtures are assembled at runtime from fragments so this
file contains no literal that secret scanners (or the scanner under test)
would match.

Run: python3 -m tests.test_security   (from the code-intel directory)
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from codebase_rag import security
from codebase_rag.config import Config

PASS = 0
FAIL = 0


def check(name: str, cond, extra: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS {name}")
    else:
        FAIL += 1
        print(f"  FAIL {name} {extra}")


# --- fake credentials, built from fragments --------------------------------
AWS_KEY = "AK" + "IA" + "Q3XJ7WMZ5TRVN2HB"  # 16 chars after AKIA
GH_TOKEN = "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5"
SLACK = "xo" + "xb-" + "1234567890-abcdefghijkl"
STRIPE = "sk" + "_live_" + "Zq8Xw3Lm9Rt2Vb6Nc4Ya7Hd1"
PEM_BEGIN = "-----BEGIN RSA " + "PRIVATE KEY-----"
PEM_BODY = "MIIEowIBAAKCAQEA" + "x7Vq2LmN8rTz4KcYb1HdQw9" + "ZpXe3JfUa6RsGt0Ny5Ml"
GENERIC = "Zq8Xw3Lm9Rt2Vb6Nc4Ya"  # varied, 20 chars


def make_repo(files: dict[str, str]) -> Config:
    root = tempfile.mkdtemp(prefix="secscan-")
    for rel, content in files.items():
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(content)
    return Config(root=root, embedder="hash")


def run(files: dict[str, str], **kw) -> tuple[dict, Config]:
    cfg = make_repo(files)
    return security.scan(cfg, **kw), cfg


def rules(res: dict, file: str | None = None) -> set[str]:
    return {f["rule_id"] for f in res["findings"] if file is None or f["file"] == file}


def main() -> int:
    cleanup: list[str] = []

    def R(files, **kw):
        res, cfg = run(files, **kw)
        cleanup.append(cfg.root)
        return res

    try:
        print("== secrets: positives + redaction ==")
        res = R(
            {
                "src/conf.py": (
                    f'AWS = "{AWS_KEY}"\n'
                    f'GH = "{GH_TOKEN}"\n'
                    f'SL = "{SLACK}"\n'
                    f'ST = "{STRIPE}"\n'
                    f'password = "{GENERIC}"\n'
                ),
                "keys/id.txt": f"{PEM_BEGIN}\n{PEM_BODY}\n-----END RSA PRIVATE KEY-----\n",
            }
        )
        got = rules(res)
        for rid in (
            "secret-aws-access-key-id",
            "secret-github-token",
            "secret-slack-token",
            "secret-stripe-live-key",
            "secret-generic-assignment",
            "secret-private-key",
        ):
            check(f"detects {rid}", rid in got, str(got))
        blob = json.dumps(res)
        for secret in (AWS_KEY, GH_TOKEN, SLACK, STRIPE, GENERIC, PEM_BODY):
            check(f"full secret never echoed ({secret[:6]}..)", secret not in blob)
        f = next(x for x in res["findings"] if x["rule_id"] == "secret-aws-access-key-id")
        check(
            "finding has file:line and remediation",
            f["file"] == "src/conf.py"
            and f["line"] == 1
            and f["remediation"]
            and f["severity"] == "high",
        )
        check("snippet carries redaction marker", "[REDACTED" in f["snippet"])

        print("== secrets: false-positive guards ==")
        res = R(
            {
                "src/ok.py": (
                    'AWS = "AKIAIOSFODNN7EXAMPLE"\n'
                    'password = "changeme-please-change"\n'
                    'api_key = "<your-api-key-goes-here>"\n'
                    'token = "${GITHUB_TOKEN_VALUE_HERE}"\n'
                    'secret = "xxxxxxxxxxxxxxxxxxxxxxxx"\n'
                    'password = os.environ["DB_PASSWORD"]\n'
                    'sha = "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c'
                    '15b0f00a08"\n'
                    'url = "https://example.com/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"\n'
                    'cfg = "postgres://user:${DB_PASS}@db/app"\n'
                    "password = 'short'\n"
                )
            }
        )
        check(
            "placeholders / env lookups / hashes not flagged",
            not res["findings"],
            str(res["findings"]),
        )
        res = R(
            {
                "a.py": f'k = "{AWS_KEY}"  # gitleaks:allow\n'
                f'j = "{AWS_KEY}"  # vegaduta:ignore[secret-aws-access-key-id]\n'
                f'z = "{AWS_KEY}"  # vegaduta:ignore[other-rule]\n'
            }
        )
        check(
            "inline allowlist suppresses; wrong rule id does not",
            [f["line"] for f in res["findings"]] == [3],
            str(res["findings"]),
        )
        res = R({"doc.md.txt": f"see {PEM_BEGIN} in the docs\nregex: {PEM_BEGIN}\n"})
        check("bare PEM header without key body not flagged", not res["findings"])
        res = R({"a.py": f'k = "{AWS_KEY}"\n', ".vegaduta-security-ignore": "# c\na.py\n"})
        check("ignore file (glob) suppresses", not res["findings"])
        res = R(
            {
                "a.py": f'k = "{AWS_KEY}"\n',
                ".vegaduta-security-ignore": "secret-aws-access-key-id:a.py\n",
            }
        )
        check("ignore file (rule:glob) suppresses", not res["findings"])
        res = R({"a.py": f'k = "{AWS_KEY}"\n', ".vegaduta-security-ignore": "py-eval-exec:a.py\n"})
        check("ignore file rule scoping respected", "secret-aws-access-key-id" in rules(res))
        res = R({"a.py": "blob = '" + "aZ3kQ9xL2mV7bN4cR8tY1wE6uI0oP5sD" + "Hj2Gf9'\n"})
        check(
            "entropy rule fires at low severity",
            rules(res) == {"secret-high-entropy-string"}
            and res["findings"][0]["severity"] == "low",
            str(res["findings"]),
        )
        res = R(
            {"a.py": "blob = '" + "aZ3kQ9xL2mV7bN4cR8tY1wE6uI0oP5sD" + "Hj2Gf9'\n"},
            min_severity="medium",
        )
        check("min_severity filters low", not res["findings"])

        print("== test / fixture paths ==")
        files = {
            "tests/test_a.py": f'k = "{AWS_KEY}"\nos.system(cmd)\n',
            "src/__fixtures__/x.py": f'k = "{AWS_KEY}"\n',
            "testdata/y.py": f'k = "{AWS_KEY}"\n',
            "src/a.test.js": f'k = "{AWS_KEY}"\n',
            "src/real.py": f'k = "{AWS_KEY}"\n',
        }
        res = R(files)
        check(
            "tests/fixtures skipped by default",
            {f["file"] for f in res["findings"]} == {"src/real.py"},
            str(res["findings"]),
        )
        check("skip count reported", res["summary"]["test_or_fixture_files_skipped"] == 4)
        res = R(files, include_tests=True)
        check("include_tests=True scans them", len({f["file"] for f in res["findings"]}) == 5)

        print("== sinks: Python ==")
        res = R(
            {
                "p.py": (
                    "os.system('ls ' + x)\n"
                    "subprocess.run(cmd, shell=True)\n"
                    "eval(user)\n"
                    "pickle.loads(data)\n"
                    "yaml.load(f)\n"
                    'cur.execute(f"SELECT * FROM t WHERE id={uid}")\n'
                    "cur.execute('SELECT * FROM t WHERE id=%s' % uid)\n"
                    "cur.execute('SELECT * FROM t WHERE id=' + uid)\n"
                    "h = hashlib.md5(x)\n"
                    "requests.get(u, verify=False)\n"
                    "c = DES.new(k)\n"
                )
            }
        )
        got = rules(res)
        for rid in (
            "py-os-system",
            "py-subprocess-shell",
            "py-eval-exec",
            "py-unsafe-deserialization",
            "py-yaml-unsafe-load",
            "py-sql-concat",
            "py-weak-hash",
            "py-tls-verify-disabled",
            "py-weak-cipher",
        ):
            check(f"detects {rid}", rid in got, str(got))
        sqls = [f["line"] for f in res["findings"] if f["rule_id"] == "py-sql-concat"]
        check("all three SQL string-building forms found", sqls == [6, 7, 8], str(sqls))
        f = res["findings"][0]
        check(
            "sink finding has rule/severity/cwe/snippet/remediation",
            all(f[k] for k in ("rule_id", "severity", "cwe", "snippet", "remediation", "file"))
            and f["line"] > 0,
        )

        print("== sinks: Python false-positive guards ==")
        res = R(
            {
                "q.py": (
                    "model.eval()\n"
                    "x = ast.literal_eval(s)\n"
                    "yaml.load(f, Loader=yaml.SafeLoader)\n"
                    "yaml.safe_load(f)\n"
                    "subprocess.run(['ls'], shell=False)\n"
                    "cur.execute('SELECT * FROM t WHERE id=%s', (uid,))\n"
                    "cur.execute('SELECT 1')\n"
                    "log.execute('hello ' + name)\n"
                    "h = hashlib.md5(x, usedforsecurity=False)\n"
                    "# os.system('commented out')\n"
                    "requests.get(u, verify=True)\n"
                    "os.system(c)  # nosec\n"
                )
            }
        )
        check(
            "benign look-alikes not flagged",
            not res["findings"],
            str(res["findings"]),
        )
        check(
            "model.eval / literal_eval / SafeLoader / parameterized SQL / nosec all clean",
            not (
                {
                    "py-os-system",
                    "py-yaml-unsafe-load",
                    "py-sql-concat",
                    "py-weak-hash",
                    "py-tls-verify-disabled",
                    "py-subprocess-shell",
                }
                & rules(res)
            ),
        )

        print("== sinks: JS/TS ==")
        res = R(
            {
                "a.js": (
                    "eval(code)\n"
                    "const f = new Function('a', body)\n"
                    "child_process.exec(`ls ${dir}`)\n"
                    "exec('rm -rf ' + p)\n"
                    "db.query(`SELECT * FROM u WHERE id = ${id}`)\n"
                    "db.query('SELECT * FROM u WHERE id=' + id)\n"
                    "crypto.createHash('md5')\n"
                    "https.request({ rejectUnauthorized: false })\n"
                    "process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'\n"
                    "serialize.unserialize(x)\n"
                ),
                "b.ts": "eval(x)\n",
            }
        )
        got = rules(res, "a.js")
        for rid in (
            "js-eval",
            "js-child-process-exec",
            "js-sql-concat",
            "js-weak-crypto",
            "js-tls-verify-disabled",
            "js-unsafe-deserialization",
        ):
            check(f"detects {rid}", rid in got, str(got))
        check("TypeScript scanned", "js-eval" in rules(res, "b.ts"))
        res = R(
            {
                "c.js": (
                    "const m = /a/.exec(s)\n"
                    "re.exec(str)\n"
                    "obj.eval(x)\n"
                    "db.query('SELECT * FROM u WHERE id=$1', [id])\n"
                    "exec('ls')\n"
                    "crypto.createHash('sha256')\n"
                    "// eval(commented)\n"
                    "https.request({ rejectUnauthorized: true })\n"
                )
            }
        )
        check(
            "JS false-positive guards (RegExp.exec, .eval, parameterized, static, sha256, comment)",
            not res["findings"],
            str(res["findings"]),
        )

        print("== sinks: Java ==")
        res = R(
            {
                "A.java": (
                    'Runtime.getRuntime().exec("ls " + d);\n'
                    "ObjectInputStream in = new ObjectInputStream(s);\n"
                    'stmt.executeQuery("SELECT * FROM t WHERE id=" + id);\n'
                    'MessageDigest.getInstance("MD5");\n'
                    'Cipher.getInstance("DES/ECB/PKCS5Padding");\n'
                    "builder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);\n"
                )
            }
        )
        got = rules(res)
        for rid in (
            "java-runtime-exec",
            "java-unsafe-deserialization",
            "java-sql-concat",
            "java-weak-crypto",
            "java-tls-verify-disabled",
        ):
            check(f"detects {rid}", rid in got, str(got))
        res = R(
            {
                "B.java": 'ps = conn.prepareStatement("SELECT * FROM t WHERE id=?");\n'
                'MessageDigest.getInstance("SHA-256");\n'
            }
        )
        check("Java parameterized SQL / SHA-256 clean", not res["findings"], str(res["findings"]))
        res = R({"c.go": 'x := "eval("\nos.system(1)\n'})
        check("sink rules are language-scoped (Go not scanned for sinks)", not res["findings"])
        res = R({"a.py": "os.system(c)\n" + f'k = "{AWS_KEY}"\n'}, categories=["secrets"])
        check("categories filter: secrets only", rules(res) == {"secret-aws-access-key-id"})
        res = R({"a.py": "os.system(c)\n" + f'k = "{AWS_KEY}"\n'}, categories=["sinks"])
        check("categories filter: sinks only", rules(res) == {"py-os-system"})

        print("== secret redaction on sink lines ==")
        res = R({"a.py": f'requests.get(u, verify=False, headers={{"k": "{AWS_KEY}"}})\n'})
        check(
            "sink snippet also redacts embedded secret",
            AWS_KEY not in json.dumps(res) and "py-tls-verify-disabled" in rules(res),
        )

        print("== dependency inventory ==")
        res = R(
            {
                "requirements.txt": "# c\nrequests==2.31.0\nFlask[async]>=2.0,<3  # web\n"
                "-r other.txt\n"
                "git+https://x/y.git\nPyYAML_Thing==1.0\nnumpy\n",
                "requirements-dev.txt": "pytest==8.0.0\n",
                "web/package.json": json.dumps(
                    {
                        "dependencies": {"react": "18.2.0", "@scope/pkg": "^1.2.0"},
                        "devDependencies": {"vitest": "~1.0.0"},
                    }
                ),
                "web/package-lock.json": json.dumps(
                    {
                        "lockfileVersion": 3,
                        "packages": {
                            "": {"dependencies": {"react": "18.2.0"}},
                            "node_modules/react": {"version": "18.2.0"},
                            "node_modules/loose-envify": {"version": "1.4.0"},
                            "node_modules/vitest": {"version": "1.0.4", "dev": True},
                        },
                    }
                ),
                "pom.xml": """<project xmlns="http://maven.apache.org/POM/4.0.0">
<properties><jackson.version>2.15.2</jackson.version></properties>
<dependencyManagement><dependencies><dependency><groupId>x</groupId><artifactId>managed</artifactId><version>1</version></dependency></dependencies></dependencyManagement>
<dependencies>
<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
<dependency><groupId>org.unversioned</groupId><artifactId>noversion</artifactId></dependency>
</dependencies></project>""",
                "go.mod": "module m\n\ngo 1.21\n\nrequire (\n\tgithub.com/gin-gonic/gin v1.9.1\n"
                "\tgolang.org/x/text v0.14.0 // indirect\n)\n"
                "require github.com/stretchr/testify v1.8.4\n",
            }
        )
        deps = {(d["ecosystem"], d["name"], d["file"]): d for d in res["dependencies"]}
        d = deps[("pypi", "requests", "requirements.txt")]
        check(
            "pypi pinned version + purl",
            d["version"] == "2.31.0" and d["purl"] == "pkg:pypi/requests@2.31.0",
        )
        d = deps[("pypi", "flask", "requirements.txt")]
        check(
            "pypi range keeps spec, no version",
            d["version"] == "" and d["spec"].startswith(">=2.0"),
        )
        check("pypi unpinned bare name listed", ("pypi", "numpy", "requirements.txt") in deps)
        check(
            "pypi -r / vcs lines skipped",
            not any(k[1].startswith(("other", "git")) for k in deps if k[0] == "pypi"),
        )
        check("requirements-dev.txt picked up", ("pypi", "pytest", "requirements-dev.txt") in deps)
        check(
            "package.json exact vs range",
            deps[("npm", "react", "web/package.json")]["version"] == "18.2.0"
            and deps[("npm", "@scope/pkg", "web/package.json")]["version"] == ""
            and deps[("npm", "@scope/pkg", "web/package.json")]["purl"].startswith(
                "pkg:npm/%40scope/pkg"
            ),
        )
        check(
            "package.json dev scope", deps[("npm", "vitest", "web/package.json")]["scope"] == "dev"
        )
        lock = deps[("npm", "loose-envify", "web/package-lock.json")]
        check(
            "lockfile transitive = indirect, direct=False",
            lock["scope"] == "indirect" and not lock["direct"],
        )
        check(
            "lockfile direct dep", deps[("npm", "react", "web/package-lock.json")]["direct"] is True
        )
        check(
            "lockfile dev flag", deps[("npm", "vitest", "web/package-lock.json")]["scope"] == "dev"
        )
        d = deps[("maven", "com.fasterxml.jackson.core:jackson-databind", "pom.xml")]
        check(
            "maven property resolved",
            d["version"] == "2.15.2"
            and d["purl"] == "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.15.2",
        )
        check(
            "maven scope + dependencyManagement excluded",
            deps[("maven", "junit:junit", "pom.xml")]["scope"] == "test"
            and ("maven", "x:managed", "pom.xml") not in deps,
        )
        check(
            "maven missing version -> empty",
            deps[("maven", "org.unversioned:noversion", "pom.xml")]["version"] == "",
        )
        check(
            "go block + single require",
            ("golang", "github.com/gin-gonic/gin", "go.mod") in deps
            and ("golang", "github.com/stretchr/testify", "go.mod") in deps,
        )
        g = deps[("golang", "golang.org/x/text", "go.mod")]
        check(
            "go indirect flagged",
            g["scope"] == "indirect" and not g["direct"] and g["version"] == "v0.14.0",
        )
        check(
            "every record has the normalized keys",
            all(
                set(x)
                == {
                    "ecosystem",
                    "name",
                    "version",
                    "spec",
                    "scope",
                    "direct",
                    "file",
                    "line",
                    "purl",
                }
                for x in res["dependencies"]
            ),
        )
        check(
            "package-lock integrity hashes produce no secret findings",
            not res["findings"],
            str(res["findings"]),
        )
        am = res["advisory_matching"]
        check(
            "states CVE matching is not performed",
            am["performed"] is False and "advisory feed" in am["note"] and res["advisories"] == [],
        )
        check(
            "disclaimer present and honest",
            "Heuristic" in res["disclaimer"] and "gitleaks" in res["disclaimer"],
        )
        res2 = R({"requirements.txt": "requests==2.31.0\n"}, categories=["secrets"])
        check("dependencies omitted when category not requested", res2["dependencies"] == [])
        res = R(
            {
                "pom.xml": '<!DOCTYPE x [<!ENTITY a "b">]><project><dependencies/></project>',
                "package.json": "{bad",
            }
        )
        check("hostile/invalid manifests yield nothing, no crash", res["dependencies"] == [])

        print("== advisory hook ==")
        seen = []

        def provider(ds):
            seen.append(len(ds))
            return [{"purl": ds[0]["purl"], "id": "TEST-1", "severity": "high"}]

        res = R({"requirements.txt": "requests==2.31.0\n"}, advisory_provider=provider)
        check(
            "advisory_provider invoked and surfaced",
            seen == [1]
            and res["advisories"][0]["id"] == "TEST-1"
            and res["advisory_matching"]["performed"],
        )

        print("== limits, ordering, determinism ==")
        files = {f"m{i}.py": f'k = "{AWS_KEY}"\n' for i in range(5)}
        files["z.py"] = f'k = "{STRIPE}"\n'
        res = R(files, limit=3)
        check(
            "limit caps findings, summary is pre-cap",
            len(res["findings"]) == 3
            and res["summary"]["findings_total"] == 6
            and res["summary"]["truncated"],
        )
        check("critical sorts first", res["findings"][0]["severity"] == "critical")
        cfg = make_repo(files)
        cleanup.append(cfg.root)
        check(
            "deterministic output", json.dumps(security.scan(cfg)) == json.dumps(security.scan(cfg))
        )
        res = R(
            {
                "node_modules/x/a.js": "eval(x)\n",
                "vendor/b.py": "os.system(c)\n",
                "big.py": "x" * 3000 + "eval(a)\n",
            }
        )
        check("vendored dirs and minified/overlong lines skipped", not res["findings"])

        print("== confinement: symlinks ==")
        outside = tempfile.mkdtemp(prefix="secscan-outside-")
        cleanup.append(outside)
        with open(os.path.join(outside, "leak.py"), "w") as f:
            f.write(f'k = "{AWS_KEY}"\n')
        cfg = make_repo({"ok.py": "x = 1\n"})
        cleanup.append(cfg.root)
        os.symlink(os.path.join(outside, "leak.py"), os.path.join(cfg.root, "link.py"))
        os.symlink(outside, os.path.join(cfg.root, "linkdir"))
        res = security.scan(cfg)
        check("symlinked file and dir are not followed", not res["findings"], str(res["findings"]))

        print("== tenant isolation (service layer) ==")
        from app.registry import RepoEntry
        from app.service import CodeIntelService, RepoNotFoundError
        from app.settings import Settings
        from app.tenancy import TenantContext

        tmp = tempfile.mkdtemp(prefix="secscan-svc-")
        cleanup.append(tmp)
        svc = CodeIntelService(
            Settings(
                data_dir=os.path.join(tmp, "data"),
                auth_mode="internal",
                internal_token="t",
                default_embedder="hash",
            )
        )
        ctxs = {t: TenantContext(tenant_id=t) for t in ("tenant_a", "tenant_b", "tenant_c")}
        for t, body in (("tenant_a", f'KEY = "{AWS_KEY}"\n'), ("tenant_b", "x = 1\n")):
            d = os.path.join(tmp, "data", "tenants", t, "src")
            os.makedirs(d)
            with open(os.path.join(d, "a.py"), "w") as f:
                f.write(body)
            subprocess.run(["git", "init", "-q", d], check=True)
            subprocess.run(["git", "-C", d, "add", "-A"], check=True)
            svc.register(ctxs[t], RepoEntry(repo_id="app", source_type="local", path=d))
        ra = svc.security_scan(ctxs["tenant_a"], "app")
        rb = svc.security_scan(ctxs["tenant_b"], "app")
        check(
            "tenant A scan sees its own secret",
            "secret-aws-access-key-id" in rules(ra),
            str(ra["findings"]),
        )
        check(
            "tenant B scan (same repo_id) sees nothing of A",
            not rb["findings"] and AWS_KEY not in json.dumps(rb),
        )
        try:
            svc.security_scan(ctxs["tenant_c"], "app")
            check("unregistered tenant -> not found", False)
        except RepoNotFoundError:
            check("unregistered tenant -> not found", True)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        for p in cleanup:
            shutil.rmtree(p, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
