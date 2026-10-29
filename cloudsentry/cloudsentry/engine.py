"""The scan engine: match checks to resources and collect findings."""

from __future__ import annotations

from typing import Iterable, List, Optional, Type

from .checks.base import REGISTRY, Check
from .models import Finding, Provider, Resource

# Importing the checks modules registers their checks as a side effect.
from .checks import aws_checks, gcp_checks, iac_checks  # noqa: F401,E402


def all_checks() -> List[Type[Check]]:
    return list(REGISTRY.values())


def select_checks(
    providers: Optional[Iterable[Provider]] = None,
    severities: Optional[Iterable] = None,
    ids: Optional[Iterable[str]] = None,
) -> List[Check]:
    """Instantiate the checks matching the given filters."""

    providers = set(providers) if providers else None
    severities = set(severities) if severities else None
    ids = set(ids) if ids else None

    selected: List[Check] = []
    for check_cls in REGISTRY.values():
        if ids is not None and check_cls.check_id not in ids:
            continue
        # provider is None for cross-provider (IaC) checks -> always included.
        if (
            providers is not None
            and check_cls.provider is not None
            and check_cls.provider not in providers
        ):
            continue
        if severities is not None and check_cls.severity not in severities:
            continue
        selected.append(check_cls())
    return selected


class Engine:
    """Runs a set of checks over a set of resources."""

    def __init__(self, checks: List[Check]):
        self.checks = checks

    def scan(self, resources: Iterable[Resource]) -> List[Finding]:
        findings: List[Finding] = []
        resources = list(resources)
        for check in self.checks:
            for resource in resources:
                if check.applies_to(resource):
                    findings.append(check.run(resource))
        return findings
