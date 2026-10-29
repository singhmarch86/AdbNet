"""JSON output. Machine-readable, good for piping into other tooling."""

from __future__ import annotations

import json
from collections import Counter
from typing import List

from ..models import Finding, Status


def render(findings: List[Finding]) -> str:
    counts = Counter(str(f.status) for f in findings)
    sev_counts = Counter(str(f.severity) for f in findings if f.status == Status.FAIL)
    payload = {
        "summary": {
            "total": len(findings),
            "pass": counts.get("PASS", 0),
            "fail": counts.get("FAIL", 0),
            "error": counts.get("ERROR", 0),
            "fail_by_severity": dict(sev_counts),
        },
        "findings": [f.to_dict() for f in findings],
    }
    return json.dumps(payload, indent=2)
