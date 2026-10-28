resource "google_service_account" "backend" {
  account_id   = "zpi-backend-${var.env}"
  display_name = "ZPI backend (${var.env})"
}

# Backend czyta hasło do bazy i klucz Brevo. Połączenie z Cloud SQL idzie przez sieć prywatną, nie przez IAM.
resource "google_secret_manager_secret_iam_member" "backend_db_password" {
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_secret_manager_secret_iam_member" "backend_brevo_api_key" {
  secret_id = google_secret_manager_secret.brevo_api_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}

# Akceptacja zaproszenia zakłada użytkownika w Firebase Authentication (Firebase Admin SDK).
resource "google_project_iam_member" "backend_firebase_auth_admin" {
  project = var.project_id
  role    = "roles/firebaseauth.admin"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

# --- CI/CD (GitHub Actions) ---
# Uwierzytelnianie przez Workload Identity Federation: GitHub podaje krótkotrwały token OIDC,
# więc nie ma kluczy service account do przechowywania w sekretach.

resource "google_service_account" "cicd" {
  account_id   = "zpi-cicd-${var.env}"
  display_name = "ZPI GitHub Actions CI/CD (${var.env})"
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github-${var.env}"
  display_name              = "GitHub Actions (${var.env})"

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  # Tylko nasze repozytorium i tylko gałąź main mogą uzyskać token (workflow CD działa na main).
  attribute_condition = "assertion.repository == \"${var.github_repository}\" && assertion.ref == \"refs/heads/main\""

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "cicd_workload_identity" {
  service_account_id = google_service_account.cicd.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

# CI może tylko wysyłać obrazy do jednego repozytorium.
resource "google_artifact_registry_repository_iam_member" "cicd_push" {
  location   = google_artifact_registry_repository.backend.location
  repository = google_artifact_registry_repository.backend.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.cicd.email}"
}

# ...oraz wdrażać nowe rewizje tylko tej jednej usługi Cloud Run.
resource "google_cloud_run_v2_service_iam_member" "cicd_deploy" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.backend.name
  role     = "roles/run.developer"
  member   = "serviceAccount:${google_service_account.cicd.email}"
}

# Wdrożenie rewizji działającej jako konto backendu wymaga prawa "act as" na tym koncie.
resource "google_service_account_iam_member" "cicd_act_as_backend" {
  service_account_id = google_service_account.backend.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.cicd.email}"
}
