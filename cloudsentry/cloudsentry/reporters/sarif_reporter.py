"""SARIF 2.1.0 output, so findings surface in GitHub code scanning / CI."""

from __future__ import annotations

import json
from typing import List

from ..models import Finding, Severity, Status

_SARIF_LEVEL = {
    Severity.CRITICAL: "error",
    Severity.HIGH: "error",
    Severity.MEDIUM: "warning",
    Severity.LOW: "note",
    Severity.INFO: "note",
}


def render(findings: List[Finding]) -> str:
    # Only failures/errors are reported as SARIF results.
    reported = [f for f in findings if f.status in (Status.FAIL, Status.ERROR)]

    rules = {}
    results = []
    for f in reported:
        if f.check_id not in rules:
            rules[f.check_id] = {
                "id": f.check_id,
                "name": f.check_id,
                "shortDescription": {"text": f.title},
                "helpUri": f.references[0] if f.references else "https://example.com/cloudsentry",
                "properties": {"security-severity": _score(f.severity)},
            }
        results.append(
            {
                "ruleId": f.check_id,
                "level": _SARIF_LEVEL.get(f.severity, "warning"),
                "message": {"text": f"{f.title}: {f.message}. {f.remediation}"},
                "locations": [
                    {
                        "physicalLocation": {
                            "artifactLocation": {"uri": f.source or f.resource_uid}
                        }
                    }
                ],
                "properties": {"resource": f.resource_uid, "provider": str(f.provider)},
            }
        )

    sarif = {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {
                    "driver": {
                        "name": "CloudSentry",
                        "informationUri": "https://example.com/cloudsentry",
                        "rules": list(rules.values()),
                    }
                },
                "results": results,
            }
        ],
    }
    return json.dumps(sarif, indent=2)


def _score(sev: Severity) -> str:
    return {
        Severity.CRITICAL: "9.5",
        Severity.HIGH: "8.0",
        Severity.MEDIUM: "5.0",
        Severity.LOW: "3.0",
        Severity.INFO: "0.0",
    }.get(sev, "5.0")
