variable "env" {
  description = "Nazwa środowiska, np. stage albo prod. Używana w nazwach zasobów."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2,8}$", var.env))
    error_message = "env musi składać się z 2-8 małych liter."
  }
}

variable "project_id" {
  description = "ID projektu GCP dla tego środowiska."
  type        = string
}

variable "region" {
  description = "Region GCP."
  type        = string
  default     = "europe-central2"
}

variable "subnet_cidr" {
  description = "Zakres podsieci dla Cloud Run (Direct VPC egress). Nie może nachodzić na inne sieci."
  type        = string
}

variable "backend_min_instances" {
  type    = number
  default = 0
}

variable "backend_max_instances" {
  type    = number
  default = 2
}

variable "allow_public_ingress" {
  description = "Czy Cloud Run ma być dostępny publicznie. Wyłącz, jeśli polityka organizacji tego zabrania."
  type        = bool
  default     = true
}

variable "db_tier" {
  description = "Typ maszyny Cloud SQL."
  type        = string
  default     = "db-f1-micro"
}

variable "db_availability_type" {
  description = "ZONAL dla stage, REGIONAL (wysoka dostępność) dla prod."
  type        = string
  default     = "ZONAL"

  validation {
    condition     = contains(["ZONAL", "REGIONAL"], var.db_availability_type)
    error_message = "Dozwolone wartości: ZONAL, REGIONAL."
  }
}

variable "db_deletion_protection" {
  description = "Ochrona przed usunięciem instancji bazy."
  type        = bool
  default     = true
}

variable "github_repository" {
  description = "Repozytorium GitHub (owner/nazwa), które może uwierzytelniać się jako service account CI/CD."
  type        = string
  default     = "studioforyou/zpi-studio"
}

variable "mail_from" {
  description = "Adres nadawcy maili z zaproszeniami. Musi być zweryfikowany w Brevo (Senders)."
  type        = string
}

variable "invitation_link_base_url" {
  description = "Adres strony, która otwiera zaproszenie w aplikacji. Backend dopisuje do niego ?token=..."
  type        = string
}
