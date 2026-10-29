"""End-to-end tests for the IaC scan path (no cloud credentials needed)."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cloudsentry import engine  # noqa: E402
from cloudsentry.collectors import iac  # noqa: E402
from cloudsentry.models import Status  # noqa: E402

FIXTURE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "examples",
    "terraform",
    "main.tf.json",
)


def _scan():
    resources = iac.collect(FIXTURE)
    findings = engine.Engine(engine.select_checks()).scan(resources)
    return resources, findings


def _fail_ids(findings):
    return {f.check_id for f in findings if f.status == Status.FAIL}


def test_resources_collected():
    resources, _ = _scan()
    types = {r.type for r in resources}
    assert "aws_s3_bucket" in types
    assert "gcp_compute_firewall" in types


def test_public_bucket_flagged():
    _, findings = _scan()
    assert "AWS-S3-001" in _fail_ids(findings)


def test_open_ssh_flagged():
    _, findings = _scan()
    assert "AWS-EC2-001" in _fail_ids(findings)


def test_public_rds_flagged():
    _, findings = _scan()
    assert "AWS-RDS-001" in _fail_ids(findings)


def test_hardcoded_secret_flagged():
    _, findings = _scan()
    assert "IAC-SEC-001" in _fail_ids(findings)


def test_gcp_firewall_rdp_flagged():
    _, findings = _scan()
    assert "GCP-FW-001" in _fail_ids(findings)


def test_secure_bucket_passes_encryption():
    _, findings = _scan()
    # The secure_data bucket should not appear as an S3-002 (encryption) failure.
    enc_fails = [
        f for f in findings
        if f.check_id == "AWS-S3-002" and f.status == Status.FAIL
        and "secure_data" in f.resource_uid
    ]
    assert not enc_fails


if __name__ == "__main__":
    res, finds = _scan()
    fails = sorted(_fail_ids(finds))
    print(f"{len(res)} resources, {len(fails)} distinct failing checks: {fails}")
