# Backend

Java 21 and Spring Boot API using PostgreSQL and Flyway. See the [architecture decisions](https://github.com/studioforyou/zpi-studio/wiki/ADR-decisions) and [API conventions](https://github.com/studioforyou/zpi-studio/wiki/API-Convention).


## Source map

- [Java source](src/main/java/) — application entry point, controllers, and business code.
- [Application configuration](src/main/resources/application.yml) — datasource and persistence settings.
- [Database migrations](src/main/resources/db/migration/) — versioned SQL schema changes.
- [Tests](src/test/java/) and [build configuration](pom.xml).

## Local development

Requires Docker with its engine running. Run the following commands from the repository root in PowerShell.

Create `.env` if it does not exist:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Set `LOCAL_DB_USER` and `LOCAL_DB_PASSWORD` in `.env`, then start the services:

```powershell
docker compose up -d --build
```

- API: `http://localhost:8080`; connection check: `GET /api/ping`.
- PostgreSQL: `localhost:5432`, database `zpi_studio`; credentials come from `.env`.
- Database only: `docker compose up -d postgres`.
- Stop the services: `docker compose down`.

## Database migrations

- Manage schema changes only through SQL files in `src/main/resources/db/migration/`.
- Use `V<version>__<description>.sql` with consecutive version numbers. Check `main` and active branches before choosing the next number.
- Never edit a migration already applied to a shared environment, including its comments. Add a new migration for corrections.
- Flyway applies pending migrations at backend startup; Hibernate validates the schema.
- All migrations require review by the database owner, @krzysieknosek.

## Tests

Requires JDK 21 and a running Docker engine for the PostgreSQL Testcontainers database. From `backend/`, run:

```powershell
.\mvnw.cmd test
```