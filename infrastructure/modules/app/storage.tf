# Files stored by the backend: photos and documents attached to messages (#149, #152).
# The bucket is private. The app downloads a file only through the backend, which checks that the
# user may see the message and streams the content, so there are no public or signed URLs.
resource "google_storage_bucket" "files" {
  # Bucket names are global, so the project ID keeps them unique.
  name     = "${var.project_id}-files"
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  # Deleting a message deletes its files for good: no soft-deleted copies are kept.
  soft_delete_policy {
    retention_duration_seconds = 0
  }

  depends_on = [google_project_service.required]
}

# The backend saves, reads and deletes objects in this bucket only.
resource "google_storage_bucket_iam_member" "backend_files" {
  bucket = google_storage_bucket.files.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.backend.email}"
}
