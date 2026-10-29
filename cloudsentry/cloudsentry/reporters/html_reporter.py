"""Self-contained HTML dashboard (no external assets), ScoutSuite-style."""

from __future__ import annotations

import html
from collections import Counter
from typing import List

from ..models import Finding, Severity, Status

_SEV_COLOR = {
    "CRITICAL": "#b00020",
    "HIGH": "#e65100",
    "MEDIUM": "#f9a825",
    "LOW": "#2e7d32",
    "INFO": "#607d8b",
}


def render(findings: List[Finding]) -> str:
    fails = [f for f in findings if f.status == Status.FAIL]
    errors = [f for f in findings if f.status == Status.ERROR]
    passes = sum(1 for f in findings if f.status == Status.PASS)
    sev_counts = Counter(str(f.severity) for f in fails)

    # Sort failures by severity (worst first), then check id.
    fails.sort(key=lambda f: (-f.severity.value, f.check_id))

    cards = "".join(
        f'<div class="card" style="border-top:4px solid {_SEV_COLOR[s]}">'
        f'<div class="n">{sev_counts.get(s, 0)}</div><div class="l">{s}</div></div>'
        for s in ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"]
    )

    rows = "".join(_row(f) for f in fails)
    if not fails:
        rows = '<tr><td colspan="5" class="ok">No failing checks. 🎉</td></tr>'

    return _TEMPLATE.format(
        cards=cards,
        passes=passes,
        fails=len(fails),
        errors=len(errors),
        rows=rows,
    )


def _row(f: Finding) -> str:
    color = _SEV_COLOR[str(f.severity)]
    comp = ", ".join(html.escape(c) for c in f.compliance)
    return (
        "<tr>"
        f'<td><span class="pill" style="background:{color}">{f.severity}</span></td>'
        f"<td><code>{html.escape(f.check_id)}</code><br><small>{comp}</small></td>"
        f"<td>{html.escape(f.title)}<br><small>{html.escape(f.message)}</small></td>"
        f"<td><code>{html.escape(f.resource_uid)}</code><br>"
        f"<small>{html.escape(str(f.provider))} · {html.escape(f.source or '')}</small></td>"
        f"<td><small>{html.escape(f.remediation)}</small></td>"
        "</tr>"
    )


_TEMPLATE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CloudSentry Report</title>
<style>
 body{{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:0;background:#0f1117;color:#e6e6e6}}
 header{{padding:24px 32px;background:#161a23;border-bottom:1px solid #262b36}}
 h1{{margin:0;font-size:20px}} .sub{{color:#8b93a7;font-size:13px;margin-top:4px}}
 .cards{{display:flex;gap:12px;flex-wrap:wrap;padding:24px 32px}}
 .card{{background:#161a23;border-radius:8px;padding:16px 20px;min-width:90px;text-align:center}}
 .card .n{{font-size:26px;font-weight:700}} .card .l{{font-size:11px;color:#8b93a7;letter-spacing:.08em}}
 .tot{{padding:0 32px;color:#8b93a7;font-size:13px}}
 table{{width:calc(100% - 64px);margin:16px 32px 48px;border-collapse:collapse;font-size:13px}}
 th,td{{text-align:left;padding:10px 12px;border-bottom:1px solid #262b36;vertical-align:top}}
 th{{color:#8b93a7;font-weight:600;text-transform:uppercase;font-size:11px;letter-spacing:.06em}}
 code{{background:#0b0d13;padding:1px 5px;border-radius:4px;font-size:12px}}
 small{{color:#8b93a7}} .pill{{color:#fff;padding:2px 8px;border-radius:10px;font-size:11px;font-weight:700}}
 .ok{{text-align:center;color:#2e7d32;padding:32px;font-size:16px}}
</style></head><body>
<header><h1>CloudSentry — Cloud Security Report</h1>
<div class="sub">Multi-cloud posture &amp; IaC scan (AWS · GCP · Terraform)</div></header>
<div class="cards">{cards}</div>
<div class="tot">{passes} passed · {fails} failed · {errors} errors</div>
<table><thead><tr><th>Severity</th><th>Check</th><th>Finding</th><th>Resource</th><th>Remediation</th></tr></thead>
<tbody>{rows}</tbody></table>
</body></html>"""
