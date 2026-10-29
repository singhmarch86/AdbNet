"""Base class and registry for security checks.

A check is a small, self-contained unit of policy. It declares metadata as
class attributes and implements :meth:`evaluate`, which returns ``None`` when
the resource is compliant or a short failure reason when it is not.

Checks register themselves automatically via ``__init_subclass__`` so that
simply importing a checks module makes its checks discoverable by the engine.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Type

from ..models import Finding, Provider, Resource, Severity, Status

# Populated automatically as Check subclasses are defined/imported.
REGISTRY: Dict[str, Type["Check"]] = {}


class Check:
    """Abstract base for all checks.

    Subclasses set the class attributes below and implement ``evaluate``.
    """

    # Unique, stable identifier, e.g. "AWS-S3-001".
    check_id: str = ""
    title: str = ""
    severity: Severity = Severity.MEDIUM
    provider: Provider = Provider.AWS
    # Canonical resource types this check applies to, e.g. ["aws_s3_bucket"].
    resource_types: List[str] = []
    remediation: str = ""
    references: List[str] = []
    compliance: List[str] = []

    def __init_subclass__(cls, **kwargs) -> None:
        super().__init_subclass__(**kwargs)
        # Register only concrete checks (those that declare an id).
        if cls.check_id:
            if cls.check_id in REGISTRY:
                raise ValueError(f"duplicate check_id: {cls.check_id}")
            REGISTRY[cls.check_id] = cls

    def applies_to(self, resource: Resource) -> bool:
        return resource.type in self.resource_types

    def evaluate(self, resource: Resource) -> Optional[str]:
        """Return ``None`` if compliant, else a short failure message.

        Subclasses override this. Raising is allowed; the engine turns an
        exception into an ``ERROR`` finding rather than crashing the scan.
        """

        raise NotImplementedError

    # -- helpers ---------------------------------------------------------

    def _finding(self, resource: Resource, status: Status, message: str) -> Finding:
        return Finding(
            check_id=self.check_id,
            title=self.title,
            severity=self.severity,
            status=status,
            resource_uid=resource.uid,
            resource_type=resource.type,
            provider=resource.provider,
            message=message,
            remediation=self.remediation,
            references=list(self.references),
            compliance=list(self.compliance),
            source=resource.source,
        )

    def run(self, resource: Resource) -> Finding:
        """Evaluate and wrap the result in a :class:`Finding`."""

        try:
            reason = self.evaluate(resource)
        except Exception as exc:  # noqa: BLE001 - surfaced as ERROR finding
            return self._finding(
                resource, Status.ERROR, f"check raised {type(exc).__name__}: {exc}"
            )
        if reason is None:
            return self._finding(resource, Status.PASS, "compliant")
        return self._finding(resource, Status.FAIL, reason)
