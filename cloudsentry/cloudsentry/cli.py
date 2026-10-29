"""Command-line interface for CloudSentry.

Uses only the standard library (argparse) so the core tool runs with no
third-party dependencies for IaC scanning. Live scanning pulls in the relevant
cloud SDK lazily inside the collector.

Examples
--------
Scan Terraform:
    python -m cloudsentry.cli iac ./examples/terraform --format table

Scan a live AWS account (needs boto3 + credentials):
    python -m cloudsentry.cli aws --region us-east-1 --format html -o report.html

Scan a live GCP project (needs google-cloud libs + ADC):
    python -m cloudsentry.cli gcp --project my-proj --format sarif -o out.sarif

List all available checks:
    python -m cloudsentry.cli list-checks
"""

from __future__ import annotations

import argparse
import sys
from typing import List

from . import engine
from .models import Finding, Provider, Severity, Status
from .reporters import html_reporter, json_reporter, sarif_reporter

_SEVERITIES = {s.name: s for s in Severity}


def _add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--format", choices=["table", "json", "html", "sarif"], default="table")
    p.add_argument("-o", "--output", help="write report to a file instead of stdout")
    p.add_argument(
        "--severity",
        help="minimum severity to report (e.g. HIGH). Default: report all.",
        choices=list(_SEVERITIES),
    )
    p.add_argument(
        "--fail-on",
        help="exit non-zero if a finding at/above this severity fails. Default: HIGH.",
        choices=list(_SEVERITIES),
        default="HIGH",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cloudsentry", description="Multi-cloud security & IaC scanner")
    sub = parser.add_subparsers(dest="command", required=True)

    p_iac = sub.add_parser("iac", help="scan Terraform files/directories")
    p_iac.add_argument("path", help="path to a .tf file or a directory")
    _add_common(p_iac)

    p_aws = sub.add_parser("aws", help="scan a live AWS account")
    p_aws.add_argument("--region", action="append", dest="regions", help="region (repeatable)")
    p_aws.add_argument("--profile", help="AWS profile name")
    _add_common(p_aws)

    p_gcp = sub.add_parser("gcp", help="scan a live GCP project")
    p_gcp.add_argument("--project", required=True, help="GCP project id")
    _add_common(p_gcp)

    sub.add_parser("list-checks", help="list all available checks")
    return parser


def _collect(args) -> List:
    if args.command == "iac":
        from .collectors import iac

        return iac.collect(args.path)
    if args.command == "aws":
        from .collectors import aws

        return aws.collect(regions=args.regions, profile=args.profile)
    if args.command == "gcp":
        from .collectors import gcp

        return gcp.collect(project=args.project)
    return []


def _filter_by_severity(findings: List[Finding], minimum: str | None) -> List[Finding]:
    if not minimum:
        return findings
    floor = _SEVERITIES[minimum].value
    # Keep all passes (for the summary) but drop low-severity failures.
    return [f for f in findings if f.status != Status.FAIL or f.severity.value >= floor]


def _render(findings: List[Finding], fmt: str) -> str:
    if fmt == "json":
        return json_reporter.render(findings)
    if fmt == "html":
        return html_reporter.render(findings)
    if fmt == "sarif":
        return sarif_reporter.render(findings)
    return _render_table(findings)


def _render_table(findings: List[Finding]) -> str:
    fails = [f for f in findings if f.status == Status.FAIL]
    fails.sort(key=lambda f: (-f.severity.value, f.check_id))
    lines = []
    for f in fails:
        lines.append(f"[{f.severity!s:8}] {f.check_id:12} {f.resource_uid}")
        lines.append(f"            {f.title} — {f.message}")
    passed = sum(1 for f in findings if f.status == Status.PASS)
    errors = sum(1 for f in findings if f.status == Status.ERROR)
    lines.append("")
    lines.append(f"Summary: {passed} passed, {len(fails)} failed, {errors} errors")
    return "\n".join(lines) if lines else "No resources matched any checks."


def _list_checks() -> str:
    rows = []
    for cls in sorted(engine.all_checks(), key=lambda c: c.check_id):
        prov = cls.provider.value if cls.provider else "any"
        rows.append(f"{cls.check_id:12} {str(cls.severity):8} {prov:4} {cls.title}")
    return "\n".join(rows)


def main(argv: List[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "list-checks":
        print(_list_checks())
        return 0

    checks = engine.select_checks()
    try:
        resources = _collect(args)
    except RuntimeError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    findings = engine.Engine(checks).scan(resources)
    findings = _filter_by_severity(findings, args.severity)

    report = _render(findings, args.format)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh:
            fh.write(report)
        print(f"wrote {args.format} report to {args.output}", file=sys.stderr)
    else:
        print(report)

    # Exit code reflects the worst failing severity, for CI gating.
    floor = _SEVERITIES[args.fail_on].value
    gating = any(
        f.status == Status.FAIL and f.severity.value >= floor for f in findings
    )
    return 1 if gating else 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
