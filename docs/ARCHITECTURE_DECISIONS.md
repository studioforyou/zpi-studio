| Number | Topic | Decision | Rationale |
| --- | --- | --- | --- |
| ADR-001 | Application architecture | A modular monolith with one deployable backend and explicit module contracts. | Coordinate workflows and transactions in one application while keeping module responsibilities and dependencies clear. |
| ADR-002 | Backend technology | Java 21, Spring Boot, and Spring Modulith. | Continue the existing Java/Spring stack and verify application module boundaries. |
| ADR-003 | Database | PostgreSQL for structured application data. | Use relational constraints and transactions to maintain data consistency. |
| ADR-004 | Cloud hosting | Google Cloud Platform: Cloud Run, Cloud SQL for PostgreSQL, and Secret Manager. | Match the planned staging architecture and use managed hosting, database, and secret storage services. |
| ADR-005 | Mobile and web clients | Flutter for mobile and React for web, both using the backend API. | Retain the existing Flutter mobile stack and the team's selected React web technology. |
| ADR-006 | Push notifications | Firebase Cloud Messaging only for push notifications; authentication and authorization handled by the application's own backend. | Separate notification delivery from application account management and access control. |
| ADR-007 | Repository structure | One monorepo for backend, mobile, web, infrastructure, and documentation. | Review related component and documentation changes together while retaining component-specific tooling. |
| ADR-008 | API | REST with JSON contracts, HTTPS in hosted environments, and OpenAPI documentation. | Provide an explicit shared contract for Flutter and React without exposing persistence entities. |
| ADR-009 | File storage | Google Cloud Storage for application file contents. | Use the previously selected storage service and keep file contents separate from structured application data. |
| ADR-010 | Infrastructure management | Terraform configuration under infrastructure/. | Make cloud resource configuration reproducible, versioned, and reviewable. |
| ADR-011 | Application identity | Stable internal account identifiers, separate from login/contact details and domain profiles. | Keep account and domain references independent of changes to credentials or contact details. |
