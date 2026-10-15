# Infrastructure

Terraform configuration for the ZPI Studio cloud environment on Google Cloud Platform. Currently only the `stage` environment exists.

## Structure

```
infrastructure/
├── modules/
│   └── app/     # Reusable stack for one environment
│       ├── main.tf          # Enabled GCP APIs
│       ├── network.tf       # VPC, subnet, private service access
│       ├── database.tf      # Cloud SQL for PostgreSQL (private IP only)
│       ├── secrets.tf       # DB password in Secret Manager
│       ├── iam.tf           # Backend service account
│       ├── registry.tf      # Artifact Registry (Docker images)
│       ├── cloud_run.tf     # Backend service on Cloud Run
│       ├── variables.tf
│       ├── outputs.tf
│       └── versions.tf
└── envs/
    └── stage/               # Stage environment, calls the module
        ├── backend.tf       # Remote state (GCS) and provider
        ├── main.tf          # Module call with stage-specific values
        ├── variables.tf
        ├── outputs.tf
        └── terraform.tfvars.example
```

Each environment lives in its own directory under `envs/` and has its own state. To add `prod`, create `envs/prod/` with the same files, a different state bucket/prefix, and different values passed to the module.

## What gets created

- VPC with a subnet for Cloud Run (Direct VPC egress) and private service access
- Cloud SQL for PostgreSQL 18 with a private IP only, daily backups and PITR
- Secret Manager secret with the generated database password
- Service account for the backend (read access to that secret only)
- Artifact Registry repository `zpi-studio`
- Cloud Run service `zpi-backend-<env>` connected to the database through the VPC

The backend receives `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` (the last one from Secret Manager). Flyway migrations run when the application starts.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) `>= 1.6`
- [Google Cloud CLI](https://cloud.google.com/sdk/docs/install)
- Access to the GCP project with permission to create the resources above, and billing enabled on it

## Usage

```bash
gcloud auth login
gcloud auth application-default login

cd infrastructure/envs/stage
cp terraform.tfvars.example terraform.tfvars   # set project_id

terraform init
terraform plan
terraform apply
```

After `apply`, `terraform output` prints the backend URL, the Artifact Registry repository and the database private IP.

The first `apply` deploys a placeholder image. The real backend image is deployed separately (CI/CD), and Terraform ignores later image changes.

## Notes

- Never commit state files, `.terraform/` or service account keys. They are covered by `.gitignore`. `terraform.tfvars` only holds the project ID, which is not a secret.
- The generated database password is stored in the Terraform state, so access to the state bucket must stay restricted to the team.
- Commit `.terraform.lock.hcl`. It pins provider versions for everyone.
- Run `terraform fmt -recursive` and `terraform validate` before opening a PR.
- The Cloud SQL instance has deletion protection enabled. Disable it in the module variables before running `terraform destroy`.
