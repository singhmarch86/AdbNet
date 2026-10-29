"""Live AWS collector (boto3).

Reads configuration from a running account and normalizes it into the same
``Resource`` shape the IaC collector produces, so the AWS checks run unchanged.

boto3 is an optional dependency and credentials are resolved by boto3's normal
chain (env vars, shared config, instance profile). If boto3 is missing the
collector raises a clear error; individual API failures are logged and skipped
so one missing permission does not abort the whole scan.
"""

from __future__ import annotations

import logging
from typing import List

from ..models import Provider, Resource

log = logging.getLogger("cloudsentry.aws")


def collect(regions: List[str] | None = None, profile: str | None = None) -> List[Resource]:
    try:
        import boto3  # type: ignore
    except ImportError as exc:  # pragma: no cover - env dependent
        raise RuntimeError("live AWS scanning requires boto3 (pip install boto3)") from exc

    session = boto3.Session(profile_name=profile) if profile else boto3.Session()
    resources: List[Resource] = []

    resources.extend(_collect_s3(session))
    resources.extend(_collect_iam(session))

    for region in regions or [session.region_name or "us-east-1"]:
        resources.extend(_collect_security_groups(session, region))
        resources.extend(_collect_rds(session, region))

    return resources


def _safe(fn, default=None):
    try:
        return fn()
    except Exception as exc:  # noqa: BLE001 - skip on permission/API errors
        log.warning("AWS API call failed: %s", exc)
        return default


def _collect_s3(session) -> List[Resource]:
    s3 = session.client("s3")
    out: List[Resource] = []
    buckets = _safe(lambda: s3.list_buckets().get("Buckets", []), [])
    for b in buckets or []:
        name = b["Name"]
        pab = _safe(
            lambda: s3.get_public_access_block(Bucket=name)["PublicAccessBlockConfiguration"],
            {},
        )
        enc = _safe(lambda: s3.get_bucket_encryption(Bucket=name), None)
        ver = _safe(lambda: s3.get_bucket_versioning(Bucket=name), {})
        blocked = all(
            pab.get(k, False)
            for k in ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")
        )
        out.append(
            Resource(
                uid=f"s3:{name}",
                type="aws_s3_bucket",
                provider=Provider.AWS,
                config={
                    "public": not blocked,
                    "encryption_enabled": enc is not None,
                    "versioning_enabled": (ver or {}).get("Status") == "Enabled",
                },
                source=f"arn:aws:s3:::{name}",
            )
        )
    return out


def _collect_iam(session) -> List[Resource]:
    iam = session.client("iam")
    out: List[Resource] = []
    users = _safe(lambda: iam.list_users().get("Users", []), [])
    for u in users or []:
        name = u["UserName"]
        mfa = _safe(lambda: iam.list_mfa_devices(UserName=name).get("MFADevices", []), [])
        profile = _safe(lambda: iam.get_login_profile(UserName=name), None)
        out.append(
            Resource(
                uid=f"iam-user:{name}",
                type="aws_iam_user",
                provider=Provider.AWS,
                config={"console_access": profile is not None, "mfa_enabled": bool(mfa)},
                source=u.get("Arn"),
            )
        )
    return out


def _collect_security_groups(session, region: str) -> List[Resource]:
    ec2 = session.client("ec2", region_name=region)
    out: List[Resource] = []
    groups = _safe(lambda: ec2.describe_security_groups().get("SecurityGroups", []), [])
    for g in groups or []:
        rules = []
        for perm in g.get("IpPermissions", []):
            frm = perm.get("FromPort")
            to = perm.get("ToPort")
            proto = perm.get("IpProtocol")
            for rng in perm.get("IpRanges", []):
                rules.append({"cidr": rng.get("CidrIp"), "from_port": frm, "to_port": to, "protocol": proto})
            for rng in perm.get("Ipv6Ranges", []):
                rules.append({"cidr": rng.get("CidrIpv6"), "from_port": frm, "to_port": to, "protocol": proto})
        out.append(
            Resource(
                uid=f"sg:{g['GroupId']}",
                type="aws_security_group",
                provider=Provider.AWS,
                region=region,
                config={"ingress_rules": rules},
                source=g["GroupId"],
            )
        )
    return out


def _collect_rds(session, region: str) -> List[Resource]:
    rds = session.client("rds", region_name=region)
    out: List[Resource] = []
    instances = _safe(lambda: rds.describe_db_instances().get("DBInstances", []), [])
    for i in instances or []:
        out.append(
            Resource(
                uid=f"rds:{i['DBInstanceIdentifier']}",
                type="aws_db_instance",
                provider=Provider.AWS,
                region=region,
                config={
                    "publicly_accessible": bool(i.get("PubliclyAccessible", False)),
                    "storage_encrypted": bool(i.get("StorageEncrypted", False)),
                },
                source=i.get("DBInstanceArn"),
            )
        )
    return out
