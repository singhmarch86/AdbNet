"""Live GCP collector (google-cloud SDKs).

Normalizes live project configuration into the same ``Resource`` shape as the
IaC collector. The google-cloud client libraries are optional dependencies and
Application Default Credentials are used. API failures are logged and skipped.
"""

from __future__ import annotations

import logging
from typing import List

from ..models import Provider, Resource

log = logging.getLogger("cloudsentry.gcp")


def collect(project: str) -> List[Resource]:
    if not project:
        raise RuntimeError("live GCP scanning requires a --project id")
    resources: List[Resource] = []
    resources.extend(_collect_storage(project))
    resources.extend(_collect_firewalls(project))
    return resources


def _collect_storage(project: str) -> List[Resource]:
    try:
        from google.cloud import storage  # type: ignore
    except ImportError as exc:  # pragma: no cover - env dependent
        raise RuntimeError(
            "live GCP storage scanning requires google-cloud-storage"
        ) from exc

    out: List[Resource] = []
    try:
        client = storage.Client(project=project)
        buckets = list(client.list_buckets())
    except Exception as exc:  # noqa: BLE001
        log.warning("GCS list_buckets failed: %s", exc)
        return out

    for b in buckets:
        public_members: List[str] = []
        try:
            policy = b.get_iam_policy(requested_policy_version=3)
            for binding in policy.bindings:
                for m in binding.get("members", []):
                    if m in {"allUsers", "allAuthenticatedUsers"}:
                        public_members.append(m)
        except Exception as exc:  # noqa: BLE001
            log.warning("GCS get_iam_policy failed for %s: %s", b.name, exc)

        ubla = bool(getattr(b, "iam_configuration", {}).get("uniformBucketLevelAccess", {}).get("enabled", False)) \
            if isinstance(getattr(b, "iam_configuration", None), dict) else bool(
            getattr(getattr(b, "iam_configuration", None), "uniform_bucket_level_access_enabled", False)
        )
        out.append(
            Resource(
                uid=f"gcs:{b.name}",
                type="gcp_storage_bucket",
                provider=Provider.GCP,
                config={"public_members": public_members, "uniform_bucket_level_access": ubla},
                source=f"gs://{b.name}",
            )
        )
    return out


def _collect_firewalls(project: str) -> List[Resource]:
    try:
        from google.cloud import compute_v1  # type: ignore
    except ImportError as exc:  # pragma: no cover - env dependent
        raise RuntimeError("live GCP firewall scanning requires google-cloud-compute") from exc

    out: List[Resource] = []
    try:
        client = compute_v1.FirewallsClient()
        rules = list(client.list(project=project))
    except Exception as exc:  # noqa: BLE001
        log.warning("compute firewalls list failed: %s", exc)
        return out

    for fw in rules:
        allowed = [
            {"protocol": a.I_p_protocol, "ports": list(a.ports)} for a in getattr(fw, "allowed", [])
        ]
        out.append(
            Resource(
                uid=f"fw:{fw.name}",
                type="gcp_compute_firewall",
                provider=Provider.GCP,
                config={
                    "direction": getattr(fw, "direction", "INGRESS"),
                    "source_ranges": list(getattr(fw, "source_ranges", [])),
                    "allowed": allowed,
                },
                source=fw.name,
            )
        )
    return out
