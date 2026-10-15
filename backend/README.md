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

Requires JDK 21 and a running Docker engine for the PostgreSQL Testcontainers database. From `backend/`, run:

```powershell
.\mvnw.cmd test
```
