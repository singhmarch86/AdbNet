"""AWS security checks.

These operate on the normalized ``Resource`` config, so each check runs
unchanged against Terraform source (pre-deploy) and against a live account
(post-deploy). The normalized config keys each check reads are documented in
its docstring.
"""

from __future__ import annotations

from typing import Optional

from ..models import Provider, Resource, Severity
from .base import Check

# Ports that should essentially never be exposed to the whole internet.
SENSITIVE_PORTS = {22: "SSH", 3389: "RDP", 3306: "MySQL", 5432: "PostgreSQL"}
OPEN_CIDRS = {"0.0.0.0/0", "::/0"}

PUBLIC_ACLS = {"public-read", "public-read-write", "authenticated-read"}


class S3PublicAcl(Check):
    """S3 bucket grants public access via its ACL.

    Reads ``config['acl']`` (str) and/or ``config['public']`` (bool).
    """

    check_id = "AWS-S3-001"
    title = "S3 bucket is publicly accessible"
    severity = Severity.CRITICAL
    provider = Provider.AWS
    resource_types = ["aws_s3_bucket"]
    remediation = "Set the bucket ACL to 'private' and enable S3 Block Public Access."
    references = ["https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html"]
    compliance = ["CIS-AWS-2.1.5"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("public") is True:
            return "bucket is publicly accessible"
        acl = resource.get("acl")
        if acl in PUBLIC_ACLS:
            return f"bucket ACL is '{acl}'"
        return None


class S3EncryptionDisabled(Check):
    """S3 bucket does not have server-side encryption enabled.

    Reads ``config['encryption_enabled']`` (bool).
    """

    check_id = "AWS-S3-002"
    title = "S3 bucket is not encrypted at rest"
    severity = Severity.HIGH
    provider = Provider.AWS
    resource_types = ["aws_s3_bucket"]
    remediation = "Enable default server-side encryption (SSE-S3 or SSE-KMS)."
    compliance = ["CIS-AWS-2.1.1"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("encryption_enabled", False) is not True:
            return "default encryption is not enabled"
        return None


class S3VersioningDisabled(Check):
    """S3 bucket does not have versioning enabled.

    Reads ``config['versioning_enabled']`` (bool).
    """

    check_id = "AWS-S3-003"
    title = "S3 bucket versioning is disabled"
    severity = Severity.MEDIUM
    provider = Provider.AWS
    resource_types = ["aws_s3_bucket"]
    remediation = "Enable versioning to protect against accidental deletion/overwrite."
    compliance = ["CIS-AWS-2.1.3"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("versioning_enabled", False) is not True:
            return "versioning is not enabled"
        return None


class SecurityGroupOpenIngress(Check):
    """Security group allows a sensitive port from the entire internet.

    Reads ``config['ingress_rules']``: a list of dicts with keys
    ``cidr`` (str), ``from_port`` (int), ``to_port`` (int), ``protocol`` (str).
    """

    check_id = "AWS-EC2-001"
    title = "Security group exposes a sensitive port to 0.0.0.0/0"
    severity = Severity.HIGH
    provider = Provider.AWS
    resource_types = ["aws_security_group"]
    remediation = "Restrict ingress CIDRs to known administrative ranges or a bastion."
    compliance = ["CIS-AWS-5.2"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        offenders = []
        for rule in resource.get("ingress_rules", []) or []:
            cidr = rule.get("cidr")
            if cidr not in OPEN_CIDRS:
                continue
            frm = rule.get("from_port")
            to = rule.get("to_port")
            if frm is None or to is None:
                continue
            for port, name in SENSITIVE_PORTS.items():
                if frm <= port <= to:
                    offenders.append(f"{name}/{port}")
        if offenders:
            return "open to the internet on " + ", ".join(sorted(set(offenders)))
        return None


class IamUserNoMfa(Check):
    """IAM user with console access but no MFA device.

    Reads ``config['mfa_enabled']`` (bool) and ``config['console_access']`` (bool).
    Primarily a live-account check.
    """

    check_id = "AWS-IAM-001"
    title = "IAM user has no MFA enabled"
    severity = Severity.HIGH
    provider = Provider.AWS
    resource_types = ["aws_iam_user"]
    remediation = "Enable a virtual or hardware MFA device for every console user."
    compliance = ["CIS-AWS-1.10"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("console_access", True) and not resource.get("mfa_enabled", False):
            return "console user without MFA"
        return None


class RdsPubliclyAccessible(Check):
    """RDS instance is reachable from the public internet.

    Reads ``config['publicly_accessible']`` (bool) and
    ``config['storage_encrypted']`` (bool).
    """

    check_id = "AWS-RDS-001"
    title = "RDS instance is publicly accessible"
    severity = Severity.HIGH
    provider = Provider.AWS
    resource_types = ["aws_db_instance"]
    remediation = "Set publicly_accessible = false and place the instance in a private subnet."
    compliance = ["CIS-AWS-2.3.2"]

    def evaluate(self, resource: Resource) -> Optional[str]:
        if resource.get("publicly_accessible", False) is True:
            return "instance is publicly accessible"
        return None
