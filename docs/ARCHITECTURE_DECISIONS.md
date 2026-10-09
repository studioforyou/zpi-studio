# Architecture decisions

[Documentation map](README.md) · [Open questions](OPEN_QUESTIONS.md) · [Issue #4](https://github.com/studioforyou/zpi-studio/issues/4)

Recorded on 2026-10-09. This document collects the architecture choices before product implementation. Decision status and implementation status are separate.

## Scope and status

The task covers ADR-001 through ADR-011. ADR-012 is omitted following the requester's scope correction. ADR-001 does not include a comparison with microservices.

**Accepted** identifies the choices confirmed by the requester during this task on 2026-10-09: React for web, Firebase only for push notifications with application-owned authentication, and the existing Google Cloud Storage selection. The original decision dates and separate team-review links were not supplied. This paragraph records the confirmation source for those statuses. Other choices remain **Proposed** for team review through the [workflow](WORKFLOW.md#step-2-submitting-changes-pull-request).

## Decision table

| ID | Decision | Status | Rationale and consequences |
| --- | --- | --- | --- |
| ADR-001 | Build one backend as a modular monolith, with explicit module contracts and internal implementation packages. | Proposed | Coordinate workflows and transactions in one application while making responsibilities and dependencies visible. Module boundaries remain the scope of #3/#31. |
| ADR-002 | Use Java 21, Spring Boot, and Spring Modulith for the backend. | Proposed | Continue the existing Java/Spring stack and verify module dependency cycles and access to internal packages. Compatible library versions and verification setup must be selected in #26. |
| ADR-003 | Use PostgreSQL for structured application data, including account data. Manage schema changes through versioned migrations. | Proposed | Use relational constraints and transactions for consistency. Migration tooling, domain schema, transaction boundaries, and recovery requirements still need agreement. |
| ADR-004 | Host the backend on Google Cloud Platform using Cloud Run, Cloud SQL for PostgreSQL, and Secret Manager. | Proposed | Follow the staging scope in #34/#35. Project, region, budget, connectivity, IAM, and environment isolation require infrastructure work. Local development remains separate. |
| ADR-005 | Use Flutter for mobile and React for the web application. Both clients consume the backend API. | Accepted | Preserve the confirmed React selection and the existing Flutter mobile direction. The team maintains separate clients; tooling, routing, state management, and hosting remain open. |
| ADR-006 | Use Firebase Cloud Messaging only for push notifications. Implement authentication and authorization in the application's own backend. | Accepted | Keep push delivery separate from application account control. Push registrations are delivery destinations, not login credentials. Registration lifecycle, notification payloads, retries, and web push scope require agreement. |
| ADR-007 | Keep backend, mobile, web, infrastructure, and documentation in one repository. | Proposed | Review related component and documentation changes together while retaining component-specific tooling. Follow the existing workflow and naming conventions; CI remains separately scoped in #29. |
| ADR-008 | Expose a REST API with JSON contracts and HTTPS in hosted environments; document product endpoints with OpenAPI. | Proposed | Provide a shared contract for Flutter and React without exposing persistence entities. Error format, pagination, compatibility policy, tooling, and authentication protocol remain open. |
| ADR-009 | Use Google Cloud Storage for application file contents. | Accepted | Record the previously selected storage provider. Proposed implementation: keep object references and metadata in PostgreSQL and authorize file access through the backend or time-limited signed URLs. Bucket configuration, file limits, retention, and upload/download flow remain open. |
| ADR-010 | Define cloud infrastructure with Terraform under `infrastructure/`, using protected state separate from repository content. | Proposed | Make intended resource changes reproducible and reviewable. Select state storage, access control, environment layout, provider versions, and secret handling in #34; sensitive values can appear in state and plans. |
| ADR-011 | Use stable internal application account identifiers, proposing UUID, separately from mutable login/contact details and domain profiles. | Proposed | Keep domain references independent of credential or contact changes. The backend establishes identity and enforces permissions using established security libraries. Identifier format, login method, sessions/tokens, expiry, revocation, invitations, recovery, and profile relationships require review. |

## Implementation status

Static inspection of `bbca44e` on 2026-10-09 found:

- A Java 21/Spring Boot backend with JPA, a PostgreSQL driver, and `GET /api/ping`; business modules, Spring Modulith, account data, authentication, and migrations are absent.
- A Flutter connection-check screen and local Docker Compose configuration for PostgreSQL and the backend.
- Placeholder web and infrastructure directories; React, FCM, Cloud Storage, Terraform, cloud deployment, and CI are not implemented.

No application startup, build, or deployment was performed for this documentation change. Existing verification limitations are recorded in [local development](LOCAL_DEVELOPMENT.md#verification-status).

## Remaining agreements

- The domain team defines module responsibilities and account/profile relationships through [#3](https://github.com/studioforyou/zpi-studio/issues/3) and [#31](https://github.com/studioforyou/zpi-studio/issues/31).
- The backend and frontend teams agree on authentication, API contracts, client tooling, and notification behaviour before implementation.
- The infrastructure team defines environment configuration, state/secret handling, and file access for [#34](https://github.com/studioforyou/zpi-studio/issues/34) and [#35](https://github.com/studioforyou/zpi-studio/issues/35).
- Architecture acceptance and backlog alignment remain tracked in [Q-004](OPEN_QUESTIONS.md#q-004--architecture-decision-acceptance) and [Q-007](OPEN_QUESTIONS.md#q-007--architecture-backlog-alignment). #4 still requests twelve records, and [#32](https://github.com/studioforyou/zpi-studio/issues/32) still has the title `Firebase Auth`; these differ from the confirmed task scope.

## References

- [Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html)
- [Spring Security password authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html) and [password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
- [PostgreSQL transactions](https://www.postgresql.org/docs/current/tutorial-transactions.html)
- [React](https://react.dev/learn) and [Flutter](https://docs.flutter.dev/) documentation
- [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging) and [registration management](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [OpenAPI Specification](https://spec.openapis.org/oas/latest.html)
- [Cloud Run, Cloud SQL, and Secret Manager](https://docs.cloud.google.com/run/docs/samples/cloudrun-connect-cloud-sql-parent-tag)
- [Cloud Storage signed URLs](https://docs.cloud.google.com/storage/docs/access-control/signed-urls)
- [Terraform sensitive data and state](https://developer.hashicorp.com/terraform/language/manage-sensitive-data)

Maintain this document using the [documentation maintenance rules](README.md#keeping-documentation-current). Keep the ADR identifiers stable, record dates and confirmation/review sources for status changes, and distinguish confirmed decisions from future implementation.
