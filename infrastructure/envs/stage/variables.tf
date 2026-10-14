variable "project_id" {
  description = "ID projektu GCP dla stage."
  type        = string
}

variable "region" {
  type    = string
  default = "europe-central2"
}

variable "allow_public_ingress" {
  type    = bool
  default = true
}
