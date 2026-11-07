# Background jobs. Cloud Run scales to zero and throttles CPU between requests, so the backend
# cannot run timers itself: Cloud Scheduler calls the job endpoint with a Google-signed OIDC token
# of its own service account, which the backend checks (audience + e-mail).

data "google_project" "current" {
  project_id = var.project_id
}

locals {
  # The deterministic Cloud Run URL. Built by hand because the service itself needs it in its
  # environment, so it cannot refer to its own `uri` attribute.
  backend_url = "https://zpi-backend-${var.env}-${data.google_project.current.number}.${var.region}.run.app"
}

resource "google_service_account" "scheduler" {
  account_id   = "zpi-scheduler-${var.env}"
  display_name = "ZPI Cloud Scheduler jobs (${var.env})"
}

# Needed when the service is not public; harmless otherwise.
resource "google_cloud_run_v2_service_iam_member" "scheduler_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.backend.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.scheduler.email}"
}

# Class reminders, retries of failed pushes and re-delivery of unfinished domain events.
resource "google_cloud_scheduler_job" "notifications" {
  name             = "zpi-notifications-${var.env}"
  description      = "Runs the backend notification job (reminders, push retries, event re-delivery)."
  region           = var.region
  schedule         = var.notification_job_schedule
  time_zone        = "Europe/Warsaw"
  attempt_deadline = "180s"

  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = "${local.backend_url}/internal/jobs/notifications/run"

    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = local.backend_url
    }
  }

  depends_on = [
    google_project_service.required,
    google_cloud_run_v2_service_iam_member.scheduler_invoker,
  ]
}
