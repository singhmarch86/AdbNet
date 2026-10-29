"""Infrastructure-as-Code specific checks.

These look at the *raw* Terraform attributes (stashed under ``config['_raw']``
by the IaC collector) rather than the normalized fields, so they catch issues
that only exist in source form -- hardcoded secrets, missing tags. They apply
across providers, so ``provider`` is ``None`` (meaning "any") and they match
every resource type.
"""

from __future__ import annotations

import re
from typing import Optional

from ..models import Resource, Severity
from .base import Check

# Heuristic patterns for secrets accidentally committed into IaC.
_SECRET_VALUE_PATTERNS = [
    re.compile(r"AKIA[0-9A-Z]{16}"),              # AWS access key id
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),  # PEM private key
]
_SECRET_KEY_NAMES = {"password", "secret", "secret_key", "private_key", "token", "api_key"}
# Terraform interpolations / variable refs are not literal secrets.
_INTERPOLATION = re.compile(r"\$\{.*\}|^var\.|^data\.|^local\.")


class HardcodedSecret(Check):
    """A resource attribute appears to contain a hardcoded secret."""

    check_id = "IAC-SEC-001"
    title = "Possible hardcoded secret in IaC"
    severity = Severity.CRITICAL
    provider = None  # cross-provider
    resource_types = ["*"]
    remediation = "Move secrets to a secrets manager / variables; never commit literals."
    compliance = ["CIS-IAC"]

    def applies_to(self, resource: Resource) -> bool:  # noqa: D401 - override
        return "_raw" in resource.config

    def evaluate(self, resource: Resource) -> Optional[str]:
        raw = resource.get("_raw", {}) or {}
        hits = []
        for key, value in _flatten(raw):
            if not isinstance(value, str):
                continue
            if _INTERPOLATION.search(value):
                continue
            if any(p.search(value) for p in _SECRET_VALUE_PATTERNS):
                hits.append(key)
            elif key.split(".")[-1].lower() in _SECRET_KEY_NAMES and len(value) >= 8:
                hits.append(key)
        if hits:
            return "literal secret in attribute(s): " + ", ".join(sorted(set(hits)))
        return None


class MissingTags(Check):
    """A taggable resource has no tags.

    Low severity / governance hygiene; tags drive cost allocation and ownership.
    """

    check_id = "IAC-TAG-001"
    title = "Resource has no tags"
    severity = Severity.LOW
    provider = None
    resource_types = ["*"]
    remediation = "Add ownership/cost-center tags to every resource."
    compliance = ["CIS-IAC"]

    # Only a subset of types are meaningfully taggable.
    _TAGGABLE = {"aws_s3_bucket", "aws_db_instance", "aws_instance", "aws_security_group"}

    def applies_to(self, resource: Resource) -> bool:  # noqa: D401 - override
        return resource.type in self._TAGGABLE and "_raw" in resource.config

    def evaluate(self, resource: Resource) -> Optional[str]:
        raw = resource.get("_raw", {}) or {}
        tags = raw.get("tags")
        if not tags:
            return "resource defines no tags"
        return None


def _flatten(obj, prefix=""):
    """Yield (dotted_key, value) pairs from a nested dict/list structure."""

    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _flatten(v, f"{prefix}.{k}" if prefix else str(k))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from _flatten(v, f"{prefix}[{i}]")
    else:
        yield prefix, obj
