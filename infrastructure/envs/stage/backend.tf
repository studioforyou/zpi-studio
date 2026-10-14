terraform {
  required_version = ">= 1.6"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.7"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "gcs" {
    bucket = "studioforyou-tfstate-stage"
    prefix = "stage"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
