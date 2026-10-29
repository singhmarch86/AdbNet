# CloudSentry

A multi-cloud security posture & Infrastructure-as-Code scanner, in the spirit
of **Prowler**, **ScoutSuite**, and **Checkov**.

CloudSentry scans **both**:

- **Infrastructure-as-Code** (Terraform `.tf` / `.tf.json`) — catch misconfigurations *before* deploy (like Checkov).
- **Live cloud accounts** (AWS & GCP) — audit what is *actually* running (like Prowler / ScoutSuite).

The key design idea: collectors normalize Terraform source *and* live-cloud API
responses into the **same resource model**, so a security check written once
runs against both code and a running account.

## Architecture

```
          ┌─────────────┐   ┌──────────────┐   ┌──────────────┐   ┌─────────────┐
 source → │  COLLECTORS │ → │  normalized  │ → │ CHECK ENGINE │ → │  REPORTERS  │ → output
          │ iac/aws/gcp │   │  Resources   │   │  (plugins)   │   │ json/html/… │
          └─────────────┘   └──────────────┘   └──────────────┘   └─────────────┘
```

- `collectors/` — `iac` (Terraform), `aws` (boto3), `gcp` (google-cloud).
- `models.py` — the normalized `Resource` and `Finding` types.
- `checks/` — self-registering plugin checks (`aws_checks`, `gcp_checks`, `iac_checks`).
- `engine.py` — matches checks to resources, produces findings.
- `reporters/` — `table`, `json`, `html` (dashboard), `sarif` (CI / GitHub code scanning).

## Install

Core engine + Terraform-JSON scanning needs **no dependencies** (stdlib only):

```bash
pip install -e .            # core
pip install -e .[hcl]       # + parse raw .tf (HCL) files
pip install -e .[aws]       # + live AWS scanning (boto3)
pip install -e .[gcp]       # + live GCP scanning (google-cloud libs)
pip install -e .[all]       # everything
```

## Usage

```bash
# Scan Terraform (works out of the box on .tf.json; .tf needs the [hcl] extra)
python -m cloudsentry.cli iac ./examples/terraform

# Live AWS account (uses boto3's normal credential chain)
python -m cloudsentry.cli aws --region us-east-1 --format html -o report.html

# Live GCP project (uses Application Default Credentials)
python -m cloudsentry.cli gcp --project my-project --format sarif -o out.sarif

# List every check
python -m cloudsentry.cli list-checks
```

Output formats: `table` (default), `json`, `html`, `sarif`.
Exit code is non-zero when a finding at/above `--fail-on` (default `HIGH`)
fails, so it drops straight into CI.

## Checks included (MVP)

| ID | Severity | Provider | What it catches |
|----|----------|----------|-----------------|
| AWS-S3-001 | CRITICAL | aws | Publicly accessible S3 bucket |
| AWS-S3-002 | HIGH | aws | S3 bucket not encrypted at rest |
| AWS-S3-003 | MEDIUM | aws | S3 bucket versioning disabled |
| AWS-EC2-001 | HIGH | aws | Security group exposes SSH/RDP/DB to 0.0.0.0/0 |
| AWS-IAM-001 | HIGH | aws | IAM console user without MFA |
| AWS-RDS-001 | HIGH | aws | Publicly accessible RDS instance |
| GCP-GCS-001 | CRITICAL | gcp | Publicly accessible Cloud Storage bucket |
| GCP-GCS-002 | MEDIUM | gcp | Uniform bucket-level access disabled |
| GCP-FW-001 | HIGH | gcp | Firewall exposes sensitive port to 0.0.0.0/0 |
| GCP-SQL-001 | HIGH | gcp | Cloud SQL public IP / no SSL |
| IAC-SEC-001 | CRITICAL | any | Hardcoded secret in IaC |
| IAC-TAG-001 | LOW | any | Resource missing tags |

## Adding a check

Subclass `Check`, set the metadata, implement `evaluate` (return `None` to pass,
or a short reason string to fail). It auto-registers on import.

```python
from cloudsentry.models import Provider, Severity
from cloudsentry.checks.base import Check

class MyCheck(Check):
    check_id = "AWS-S3-999"
    title = "Example"
    severity = Severity.MEDIUM
    provider = Provider.AWS
    resource_types = ["aws_s3_bucket"]

    def evaluate(self, resource):
        if resource.get("some_flag") is not True:
            return "some_flag is not set"
        return None
```

## Testing

```bash
python tests/test_iac.py     # no credentials needed
# or, with pytest installed:
pytest -q
```

## Status & roadmap

This is an MVP scaffold with a working engine, collectors, checks, and
reporters. Natural next steps: Azure collector, full CIS benchmark coverage,
a Rego/OPA policy backend, CloudFormation/Kubernetes IaC parsers, and
incremental/parallel scanning for large accounts.

> Note: CloudSentry is a **defensive** tool and is deliberately kept as a
> separate project from the rest of this repository.
