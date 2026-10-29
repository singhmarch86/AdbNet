"""GCP security checks.

Like the AWS checks, these read the normalized ``Resource`` config and run
against both Terraform source and a live project.
"""

from __future__ import annotations

from typing import Optional

from ..models import Provider, Resource, Severity
from .base import Check

PUBLIC_MEMBERS = {"allUsers", "allAuthenticatedUsers"}
SENSITIVE_PORTS = {"22": "SSH", "3389": "RDP", "3306": "MySQL", "5432": "PostgreSQL"}
OPEN_RANGES = {"0.0.0.0/0", "::/0"}


class GcsBucketPublic(Check):
    """Cloud Storage bucket grants access to allUsers/allAuthenticatedUsers.

    Reads ``config['public_members']`` (list[str]) -- IAM members on the bucket.
    """

    check_id = "GCP-GCS-001"
    title = "Cloud Storage bucket is publicly accessible"
    severity = Severity.CRITICAL
    provider = Provider.GCP
    resource_types = ["gcp_storage_bucket"]
    remediation = "Remove allUsers/allAuthenticatedUsers IAM bindings from the bucket."
    compliance = ["CIS-GCP-5.1"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        members = set(resource.get("public_members", []) or [])
        public = members & PUBLIC_MEMBERS
        if public:
            return "bucket is readable by " + ", ".join(sorted(public))
        return None


class GcsUniformAccessDisabled(Check):
    """Bucket does not enforce uniform bucket-level access.

    Reads ``config['uniform_bucket_level_access']`` (bool).
    """

    check_id = "GCP-GCS-002"
    title = "Cloud Storage bucket does not enforce uniform bucket-level access"
    severity = Severity.MEDIUM
    provider = Provider.GCP
    resource_types = ["gcp_storage_bucket"]
    remediation = "Enable uniform bucket-level access to disable per-object ACLs."
    compliance = ["CIS-GCP-5.2"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("uniform_bucket_level_access", False) is not True:
            return "uniform bucket-level access is disabled"
        return None


class FirewallOpenIngress(Check):
    """Firewall rule allows a sensitive port from any source range.

    Reads ``config['direction']`` (str), ``config['source_ranges']`` (list[str])
    and ``config['allowed']`` (list of {protocol, ports: list[str]}).
    """

    check_id = "GCP-FW-001"
    title = "Firewall rule exposes a sensitive port to 0.0.0.0/0"
    severity = Severity.HIGH
    provider = Provider.GCP
    resource_types = ["gcp_compute_firewall"]
    remediation = "Scope source_ranges to trusted networks or use IAP for admin access."
    compliance = ["CIS-GCP-3.6", "CIS-GCP-3.7"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if (resource.get("direction", "INGRESS") or "INGRESS").upper() != "INGRESS":
            return None
        ranges = set(resource.get("source_ranges", []) or [])
        if not (ranges & OPEN_RANGES):
            return None
        offenders = []
        for rule in resource.get("allowed", []) or []:
            ports = rule.get("ports") or []
            # An empty ports list on tcp/udp means "all ports".
            if not ports and rule.get("protocol") in {"tcp", "udp", "all"}:
                offenders.append(f"{rule.get('protocol')}/all")
                continue
            for port in ports:
                if str(port) in SENSITIVE_PORTS:
                    offenders.append(f"{SENSITIVE_PORTS[str(port)]}/{port}")
        if offenders:
            return "open to the internet on " + ", ".join(sorted(set(offenders)))
        return None


class CloudSqlPublicIp(Check):
    """Cloud SQL instance has a public IP or does not require SSL.

    Reads ``config['public_ip']`` (bool) and ``config['require_ssl']`` (bool).
    """

    check_id = "GCP-SQL-001"
    title = "Cloud SQL instance is exposed or does not require SSL"
    severity = Severity.HIGH
    provider = Provider.GCP
    resource_types = ["gcp_sql_database_instance"]
    remediation = "Disable the public IP and set require_ssl = true."
    compliance = ["CIS-GCP-6.4", "CIS-GCP-6.5"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        problems = []
        if resource.get("public_ip", False) is True:
            problems.append("has a public IP")
        if resource.get("require_ssl", False) is not True:
            problems.append("does not require SSL")
        if problems:
            return "; ".join(problems)
        return None
