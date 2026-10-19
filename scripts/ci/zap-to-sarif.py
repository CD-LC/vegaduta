#!/usr/bin/env python3
"""Convert an OWASP ZAP JSON report (zap-baseline.py -J report.json) to SARIF.

Code scanning has no URL-native location type, so each alert instance's URI is
stored as the artifactLocation (the region startLine GitHub expects is fixed
at 1) and the alert text carries the method and URI. partialFingerprints keep
alerts deduplicated across weekly runs.

Usage: zap-to-sarif.py <report_json.json> <out.sarif>
"""
import json
import re
import sys
import urllib.parse

# ZAP riskcode -> SARIF level. High is the only error: medium/low/info are
# findings to review, not gate material (the baseline is report-only anyway).
RISK_TO_LEVEL = {"3": "error", "2": "warning", "1": "note", "0": "note"}
RISK_TO_SCORE = {"3": "9.0", "2": "6.0", "1": "3.0", "0": "0.0"}
HTML_TAG = re.compile(r"<[^>]+>")


def clean(text):
    """Strip the HTML markup ZAP puts in desc/solution/reference fields."""
    return HTML_TAG.sub("", text or "").strip()


def main():
    if len(sys.argv) != 3:
        sys.exit(f"usage: {sys.argv[0]} <zap-report.json> <out.sarif>")
    with open(sys.argv[1], encoding="utf-8") as fh:
        report = json.load(fh)

    rules = {}
    results = []
    for site in report.get("site", []):
        for alert in site.get("alerts", []):
            plugin = str(alert.get("pluginid") or "unknown")
            name = alert.get("name") or plugin
            risk = str(alert.get("riskcode", "0"))
            if plugin not in rules:
                rules[plugin] = {
                    "id": plugin,
                    "name": re.sub(r"[^A-Za-z0-9_-]+", "", name) or plugin,
                    "shortDescription": {"text": name},
                    "fullDescription": {"text": clean(alert.get("desc")) or name},
                    "help": {
                        "text": clean(alert.get("solution")) or name,
                        "markdown": clean(alert.get("solution")) or name,
                    },
                    "properties": {
                        "security-severity": RISK_TO_SCORE.get(risk, "0.0"),
                        "zap-risk": alert.get("riskdesc", ""),
                    },
                }
            for inst in alert.get("instances") or []:
                uri = inst.get("uri") or site.get("@name", "")
                results.append(
                    {
                        "ruleId": plugin,
                        "level": RISK_TO_LEVEL.get(risk, "note"),
                        "message": {
                            "text": f"{name}: {inst.get('method', 'GET')} {uri}"
                        },
                        "locations": [
                            {
                                "physicalLocation": {
                                    "artifactLocation": {"uri": uri},
                                    "region": {"startLine": 1},
                                }
                            }
                        ],
                        "partialFingerprints": {
                            "zapAlert": (
                                f"{plugin}:"
                                f"{urllib.parse.urlsplit(uri).path}:"
                                f"{inst.get('param', '')}"
                            ),
                        },
                    }
                )

    sarif = {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "OWASP ZAP",
                        "informationUri": "https://www.zaproxy.org/",
                        "version": str(report.get("@version", "")),
                        "rules": list(rules.values()),
                    }
                },
                "results": results,
            }
        ],
    }
    with open(sys.argv[2], "w", encoding="utf-8") as fh:
        json.dump(sarif, fh, indent=2)
        fh.write("\n")
    print(
        f"zap-to-sarif: {len(results)} result(s) across {len(rules)} rule(s)"
        f" -> {sys.argv[2]}"
    )


if __name__ == "__main__":
    main()
