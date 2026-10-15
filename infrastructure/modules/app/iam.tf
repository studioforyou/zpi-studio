resource "google_service_account" "backend" {
  account_id   = "zpi-backend-${var.env}"
  display_name = "ZPI backend (${var.env})"
}

# Backend czyta tylko hasło do bazy. Połączenie z Cloud SQL idzie przez sieć prywatną, nie przez IAM.
resource "google_secret_manager_secret_iam_member" "backend_db_password" {
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}
