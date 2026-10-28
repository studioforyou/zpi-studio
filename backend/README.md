# Backend

Java 21 and Spring Boot API using PostgreSQL and Flyway. See the [architecture decisions](https://github.com/studioforyou/zpi-studio/wiki/ADR-Decisions) and [API conventions](https://github.com/studioforyou/zpi-studio/wiki/API-Convention).

## Source map

- [Java source](src/main/java/) — application entry point, controllers, and business code.
- [Application configuration](src/main/resources/application.yml) — datasource and persistence settings.
- [Database migrations](src/main/resources/db/migration/) — versioned SQL schema changes.
- [Tests](src/test/java/) and [build configuration](pom.xml).

## Local development

Follow [Database instructions](https://github.com/studioforyou/zpi-studio/wiki/Database-instructions) for database setup, connection details, and migration rules.

The [Docker Compose configuration](../docker-compose.yml) defines the local backend and database services.

- API address: `http://localhost:8080`.
- Connection check: `GET /api/ping`.

## Tests

Requires JDK 21. From `backend/`, run only the module architecture test without a Spring context, database, or Docker:

```powershell
.\mvnw.cmd -B '-Dtest=ModuleArchitectureTest' test
```

Run the full backend verification with a running Docker engine. Integration tests use separate, disposable PostgreSQL databases managed by Testcontainers:

```powershell
.\mvnw.cmd -B clean verify
```

On Linux or macOS, use `./mvnw` instead of `.\mvnw.cmd`. The existing Backend CI runs `./mvnw -B verify`, including the architecture test, and checks the Docker image build.

## Application modules

Spring Modulith 2.1.1 uses default module detection: direct subpackages of `studioforyou.pl.backend`, the package containing `BackendApplication`, are modules. All 18 modules have a `package-info.java` declaration with `@ApplicationModule` and a short responsibility description:

- `identity`, `customer`, `staff`, `offering`, `trainings`, `scheduling`, `booking`, `membership`, `documents`, `communication`, `payments`, `reporting`, `integrations`, `configuration`, `files`, `audit`, `shared`, and `system`.

Annotated package declarations are sufficient for detection, including modules without implementation classes. Group and personal training share `trainings`. The declarations establish module boundaries; future business functionality is not implemented by this skeleton.

Before implementing a backend use case or changing persistence, read [Backend modules](https://github.com/studioforyou/zpi-studio/wiki/Backend-modules) and its [instructions for using and maintaining the map](https://github.com/studioforyou/zpi-studio/wiki/Backend-modules#5-how-to-use-and-maintain-this-map). It is the working map of module responsibilities, table ownership, and expected collaboration for the full functional scope. Add concrete operations, data types, events, and dependencies with the use case that requires them; document detailed contracts and business rules on the relevant module or use-case Wiki pages.

When responsibilities, table ownership, or collaboration change, update the map as part of the same work, adjust the affected code and architecture checks where applicable, and link the relevant Wiki pages or revisions in the pull request. Include any affected contract and rule documentation in that same work. Each module accesses only its own tables; other modules use its public operations or events.

The existing `configuration`, `shared`, and `system` implementations remain in place. The `system.internal` package contains `PingController`, preserving `GET /api/ping` and its existing response; `configuration` still provides `GET /api/v1/studio`. `offering` provides the pass product catalogue (`/api/v1/pass-products`, changed only by `ADMIN` accounts) and the service catalogue (`/api/v1/service-types`): active accounts can read service definitions, while administrators create and edit their participation rules and activate or deactivate them. Existing pass and room references remain unchanged. `customer` provides a search of active participants for administrators (`GET /api/v1/customers`). `membership` lets administrators assign passes to participants and list them (`/api/v1/customers/{customerId}/passes`); it reads variant terms through `offering`'s `PassVariantLookup`, participants through `customer`'s `CustomerProfiles`, and today's date and the currency through `configuration`'s `StudioSettings`.

The `staff` module provides read and update operations for the signed-in trainer's personal details through `GET` and `PATCH /api/v1/me/staff-profile`, available to active accounts with the `TRAINER` role.

`scheduling` manages rooms and the service types allowed in them under `/api/v1/resources` (#133), for `ADMIN` accounts only; it reads service types through `offering`'s `ServiceTypes`.

Service definitions include booking and cancellation settings managed through `configuration`'s public `BookingPolicies` contract. `GET /api/v1/service-types/booking-defaults` returns studio defaults; a service returns its explicit overrides and effective settings. Saving a definition and its rules is atomic. Policy changes create new versions in `booking_policy`, preserving existing references; this use case stores configuration without executing booking or cancellation workflows.

The trainer profile controller and HTTP DTOs live in `staff.internal`, alongside the module's persistence implementation. They obtain the active account ID through `shared.api.AccountAuthentication`, implemented by the verified identity token. The existing `identity` session lookup still uses the public `StaffProfiles` contract, without introducing a reverse `staff` dependency on `identity`.

`scheduling` also holds the studio's schedule (#130, #131): one-off and recurring events with their staff and rooms (`/api/v1/schedule-events`, `/api/v1/recurrence-series`), for `ADMIN` accounts only. It reads staff members, their qualifications, leave and working hours through `staff`'s `StaffProfiles` and `StaffAvailability`, and the studio's time zone through `configuration`'s `StudioSettings`. `staff` lists staff members (`GET /api/v1/staff`) and `trainings` lists training groups (`GET /api/v1/training-groups`) for administrators.

`reporting` counts active and new customers in a period of studio dates for administrators (#163, `GET /api/v1/reports/customer-activity`), together with the period of the same length right before it. It owns no tables and assembles the counts through public read operations: `membership`'s `PassHolders` (participants with a valid pass), `scheduling`'s `ScheduledEvents` (events that were not cancelled) and `booking`'s `Participations` (participants with an active reservation) give the active customers; `identity`'s `AccountStatistics` (activated client accounts) and `customer`'s `CustomerProfiles` (profiles without an account) give the new ones. Administrators create other administrators' accounts directly, without an invitation e-mail (`POST /api/v1/admin-accounts`): the backend generates a strong password and returns it only in that response, so the creating administrator can pass it on privately (the new administrator changes it in the panel's account menu, or with the password reset on the login page, both handled by Firebase). The account is `ACTIVE` at once, gets a staff profile, and its Firebase user is only ever created, never taken over, so an e-mail already used in Firebase returns `409 ACCOUNT_ALREADY_EXISTS`.

Modules retain their default closed boundaries. Public types in a module's base package form its default API. Subpackages are internal unless explicitly exposed with `@NamedInterface`. The `shared.api` package declares `@NamedInterface("api")`, exposing the existing common API types to `configuration` without opening the whole `shared` module.

`ModuleArchitectureTest` checks that `ApplicationModules.of(BackendApplication.class)` discovers exactly the 18 modules listed above, including those containing only package declarations, and calls `verify()` to check module boundaries and dependency cycles. It runs without starting a Spring context, database, or Docker.
