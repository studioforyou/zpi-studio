resource "google_secret_manager_secret" "db_password" {
  secret_id = "zpi-${var.env}-db-password"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}

# Klucz API Brevo do wysyłki maili. Terraform tworzy tylko sekret, a wartość dodaje się ręcznie
# (gcloud secrets versions add), żeby klucz nie trafił do stanu Terraforma.
resource "google_secret_manager_secret" "brevo_api_key" {
  secret_id = "zpi-${var.env}-brevo-api-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}
