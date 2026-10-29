"""Collector for Infrastructure-as-Code (Terraform).

Parses ``.tf`` files (via python-hcl2) and ``.tf.json`` files and normalizes
each resource block into a :class:`Resource`. The original attributes are kept
under ``config['_raw']`` so IaC-specific checks can inspect them.

python-hcl2 is an optional dependency: ``.tf.json`` parsing works with the
standard library alone, so the collector degrades gracefully when hcl2 is not
installed.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, Iterable, List

from ..models import Provider, Resource

# Map Terraform resource types -> (normalized type, provider).
_TYPE_MAP = {
    "aws_s3_bucket": ("aws_s3_bucket", Provider.AWS),
    "aws_security_group": ("aws_security_group", Provider.AWS),
    "aws_db_instance": ("aws_db_instance", Provider.AWS),
    "aws_iam_user": ("aws_iam_user", Provider.AWS),
    "aws_instance": ("aws_instance", Provider.AWS),
    "google_storage_bucket": ("gcp_storage_bucket", Provider.GCP),
    "google_compute_firewall": ("gcp_compute_firewall", Provider.GCP),
    "google_sql_database_instance": ("gcp_sql_database_instance", Provider.GCP),
}


def collect(path: str) -> List[Resource]:
    """Collect resources from a Terraform file or directory tree."""

    files: List[str] = []
    if os.path.isdir(path):
        for root, _dirs, names in os.walk(path):
            for name in names:
                if name.endswith(".tf") or name.endswith(".tf.json"):
                    files.append(os.path.join(root, name))
    else:
        files.append(path)

    resources: List[Resource] = []
    for f in files:
        resources.extend(_parse_file(f))
    return resources


def _parse_file(path: str) -> List[Resource]:
    with open(path, "r", encoding="utf-8") as fh:
        text = fh.read()
    if path.endswith(".tf.json"):
        data = json.loads(text)
    else:
        data = _load_hcl(text, path)
    return list(_resources_from_body(data, path))


def _load_hcl(text: str, path: str) -> Dict[str, Any]:
    try:
        import hcl2  # type: ignore
    except ImportError as exc:  # pragma: no cover - env dependent
        raise RuntimeError(
            f"parsing {path} requires python-hcl2 (pip install python-hcl2); "
            "or provide Terraform JSON (.tf.json / `terraform show -json`)"
        ) from exc
    import io

    return hcl2.load(io.StringIO(text))


def _resources_from_body(data: Dict[str, Any], path: str) -> Iterable[Resource]:
    """Yield Resources from a parsed Terraform body.

    Handles both the hcl2 shape (``{"resource": [{type: {name: {...}}}]}``)
    and the plain ``.tf.json`` shape (``{"resource": {type: {name: {...}}}}``).
    """

    blocks = data.get("resource")
    if not blocks:
        return
    if isinstance(blocks, dict):
        blocks = [blocks]
    for block in blocks:
        for tf_type, named in block.items():
            # named: {name: attrs} or [{name: attrs}]
            entries = named if isinstance(named, list) else [named]
            for entry in entries:
                for name, attrs in entry.items():
                    attrs = attrs[0] if isinstance(attrs, list) else attrs
                    yield _normalize(tf_type, name, attrs or {}, path)


def _normalize(tf_type: str, name: str, attrs: Dict[str, Any], path: str) -> Resource:
    norm_type, provider = _TYPE_MAP.get(tf_type, (tf_type, Provider.AWS))
    config: Dict[str, Any] = {"_raw": attrs}

    if norm_type == "aws_s3_bucket":
        acl = attrs.get("acl", "private")
        config.update(
            acl=acl,
            public=acl in {"public-read", "public-read-write"},
            encryption_enabled="server_side_encryption_configuration" in attrs,
            versioning_enabled=_tf_versioning(attrs.get("versioning")),
        )
    elif norm_type == "aws_security_group":
        config["ingress_rules"] = _tf_ingress(attrs.get("ingress"))
    elif norm_type == "aws_db_instance":
        config.update(
            publicly_accessible=bool(attrs.get("publicly_accessible", False)),
            storage_encrypted=bool(attrs.get("storage_encrypted", False)),
        )
    elif norm_type == "aws_iam_user":
        # Source form can't prove MFA; assume console access, unknown MFA.
        config.update(console_access=True, mfa_enabled=False)
    elif norm_type == "gcp_storage_bucket":
        config.update(
            uniform_bucket_level_access=bool(attrs.get("uniform_bucket_level_access", False)),
            public_members=[],
        )
    elif norm_type == "gcp_compute_firewall":
        config.update(
            direction=attrs.get("direction", "INGRESS"),
            source_ranges=attrs.get("source_ranges", []) or [],
            allowed=_tf_allowed(attrs.get("allow")),
        )
    elif norm_type == "gcp_sql_database_instance":
        settings = _first(attrs.get("settings")) or {}
        ip_cfg = _first(settings.get("ip_configuration")) or {}
        config.update(
            public_ip=bool(ip_cfg.get("ipv4_enabled", True)),
            require_ssl=bool(ip_cfg.get("require_ssl", False)),
        )

    return Resource(
        uid=f"{tf_type}.{name}",
        type=norm_type,
        provider=provider,
        config=config,
        source=path,
    )


# -- small Terraform-shape helpers --------------------------------------

def _first(value):
    if isinstance(value, list):
        return value[0] if value else None
    return value


def _tf_versioning(value) -> bool:
    v = _first(value)
    return bool(v.get("enabled")) if isinstance(v, dict) else False


def _tf_ingress(value) -> List[Dict[str, Any]]:
    rules = []
    if value is None:
        return rules
    for blk in value if isinstance(value, list) else [value]:
        if not isinstance(blk, dict):
            continue
        for cidr in blk.get("cidr_blocks", []) or []:
            rules.append(
                {
                    "cidr": cidr,
                    "from_port": blk.get("from_port"),
                    "to_port": blk.get("to_port"),
                    "protocol": blk.get("protocol"),
                }
            )
    return rules


def _tf_allowed(value) -> List[Dict[str, Any]]:
    out = []
    if value is None:
        return out
    for blk in value if isinstance(value, list) else [value]:
        if isinstance(blk, dict):
            out.append({"protocol": blk.get("protocol"), "ports": blk.get("ports", []) or []})
    return out
