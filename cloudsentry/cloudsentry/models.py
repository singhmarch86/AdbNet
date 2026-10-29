"""Core data models shared across collectors, checks and reporters.

Everything in CloudSentry speaks in terms of two objects:

* ``Resource`` -- a normalized piece of cloud configuration, produced by a
  collector (an IaC file parser or a live-cloud API collector).
* ``Finding``  -- the outcome of running one ``Check`` against one ``Resource``.

Collectors normalize both Terraform source *and* live AWS/GCP API responses
into the same ``Resource`` shape, so a single check can be evaluated against
code (pre-deploy) and against a running account (post-deploy).
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class Severity(enum.Enum):
    """Relative impact of a failed check. Ordering is used for sorting."""

    CRITICAL = 4
    HIGH = 3
    MEDIUM = 2
    LOW = 1
    INFO = 0

    def __str__(self) -> str:  # pragma: no cover - trivial
        return self.name


class Status(enum.Enum):
    """Outcome of evaluating a check against a resource."""

    PASS = "PASS"
    FAIL = "FAIL"
    ERROR = "ERROR"  # the check raised while evaluating the resource

    def __str__(self) -> str:  # pragma: no cover - trivial
        return self.value


class Provider(enum.Enum):
    AWS = "aws"
    GCP = "gcp"

    def __str__(self) -> str:  # pragma: no cover - trivial
        return self.value


@dataclass
class Resource:
    """A normalized cloud resource.

    ``config`` holds provider-agnostic keys that checks read. Both the IaC and
    the live collectors populate the same keys for a given ``type`` so a check
    written once works against code and against a running account.
    """

    uid: str                       # stable identity within a scan
    type: str                      # canonical type, e.g. "aws_s3_bucket"
    provider: Provider
    config: Dict[str, Any] = field(default_factory=dict)
    region: Optional[str] = None
    # Where this resource came from: a file path (+line) for IaC, or an ARN /
    # self-link for a live resource. Purely informational for reports.
    source: Optional[str] = None

    def get(self, key: str, default: Any = None) -> Any:
        return self.config.get(key, default)


@dataclass
class Finding:
    """One check applied to one resource."""

    check_id: str
    title: str
    severity: Severity
    status: Status
    resource_uid: str
    resource_type: str
    provider: Provider
    message: str = ""
    remediation: str = ""
    references: List[str] = field(default_factory=list)
    compliance: List[str] = field(default_factory=list)  # e.g. ["CIS-AWS-2.1.1"]
    source: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "check_id": self.check_id,
            "title": self.title,
            "severity": str(self.severity),
            "status": str(self.status),
            "resource_uid": self.resource_uid,
            "resource_type": self.resource_type,
            "provider": str(self.provider),
            "message": self.message,
            "remediation": self.remediation,
            "references": list(self.references),
            "compliance": list(self.compliance),
            "source": self.source,
        }
