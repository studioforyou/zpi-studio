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
.\mvnw.cmd -B -Dtest=ModuleArchitectureTest test
```

Run the full backend verification with a running Docker engine. Integration tests use separate, disposable PostgreSQL databases managed by Testcontainers:

```powershell
.\mvnw.cmd -B clean verify
```

On Linux or macOS, use `./mvnw` instead of `.\mvnw.cmd`. The existing Backend CI runs `./mvnw -B verify`, including the architecture test, and checks the Docker image build.

## Application modules

Spring Modulith 2.1.1 uses default module detection: direct subpackages of `studioforyou.pl.backend`, the package containing `BackendApplication`, are modules. Currently these are `configuration`, `shared`, and `system`; the business module boundaries will be decided separately.

The technical `system` module is declared with `@ApplicationModule`. Its `internal` package contains `PingController`, preserving `GET /api/ping` and its existing response.

Public types in a module's base package form its default API. Subpackages are internal unless explicitly exposed with `@NamedInterface`. The `shared.api` package declares `@NamedInterface("api")`, exposing the existing common API types to `configuration` without opening the whole `shared` module.

`ModuleArchitectureTest` checks that `system` is discovered and calls `ApplicationModules.of(BackendApplication.class).verify()` to check module boundaries and cycles.
