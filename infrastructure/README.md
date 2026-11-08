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
│       ├── scheduler.tf     # Cloud Scheduler job for notifications
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
- Secret Manager secrets: the generated database password, and the Brevo API key (the secret only, its value is added by hand)
- Service account for the backend (read access to those secrets, Firebase Authentication Admin for accepting invitations)
- Artifact Registry repository `zpi-studio`
- Cloud Run service `zpi-backend-<env>` connected to the database through the VPC
- Firebase Cloud Messaging access for the backend (`roles/firebasecloudmessaging.admin`)
- Service account `zpi-scheduler-<env>` and the Cloud Scheduler job `zpi-notifications-<env>`, which calls `POST /internal/jobs/notifications/run` every 5 minutes with that account's OIDC token

The backend receives `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` (the last one from Secret Manager), and `FIREBASE_PROJECT_ID` set to the GCP project ID, because Firebase Authentication runs in the same project. Flyway migrations run when the application starts.

For e-mail invitations it also gets `MAIL_PROVIDER=BREVO`, `MAIL_FROM` (the `mail_from` variable, a sender verified in Brevo), `BREVO_API_KEY` (from Secret Manager) and `INVITATION_LINK_BASE_URL`.

### Notifications

The backend sends push notifications through FCM (`PUSH_PROVIDER=FCM`, with the backend service account's credentials). Cloud Run scales to zero and has no CPU between requests, so timed work (class reminders, retries of failed pushes, re-delivery of unfinished domain events) is triggered from outside: Cloud Scheduler calls the job endpoint every 5 minutes (`notification_job_schedule`). The request carries a Google-signed OIDC token of `zpi-scheduler-<env>`. The backend accepts it only with audience `JOBS_OIDC_AUDIENCE` (the service URL) and e-mail `JOBS_OIDC_SERVICE_ACCOUNT`, and Terraform sets both. The service URL is built from the project number (`https://zpi-backend-<env>-<number>.<region>.run.app`), because the service cannot refer to its own `uri`.

To run the job by hand: `gcloud scheduler jobs run zpi-notifications-<env> --location=<region> --project=<project_id>`.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) `>= 1.16`
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

### Brevo API key

Terraform creates the secret `zpi-<env>-brevo-api-key` but not its value, so the key never lands in the Terraform state. Cloud Run cannot start a revision while the secret has no value, so on the first apply after adding it:

```bash
terraform apply -target=module.app.google_secret_manager_secret.brevo_api_key
printf '%s' 'xkeysib-...' | gcloud secrets versions add zpi-stage-brevo-api-key --data-file=- --project=<project_id>
terraform apply
```

To change the key later, add a new version the same way and deploy a new revision (or run `terraform apply`).

After `apply`, `terraform output` prints the backend URL, the Artifact Registry repository and the database private IP.

The first `apply` deploys a placeholder image. The real backend image is deployed separately (CI/CD), and Terraform ignores later image changes.

## Notes

- Never commit state files, `.terraform/` or service account keys. They are covered by `.gitignore`. `terraform.tfvars` only holds the project ID and the sender address, which are not secrets.
- The generated database password is stored in the Terraform state, so access to the state bucket must stay restricted to the team.
- Commit `.terraform.lock.hcl`. It pins provider versions for everyone.
- Run `terraform fmt -recursive` and `terraform validate` before opening a PR.
- The Cloud SQL instance has deletion protection enabled. Disable it in the module variables before running `terraform destroy`.
