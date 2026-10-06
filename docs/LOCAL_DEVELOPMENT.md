# Local development

This page describes the existing skeleton and its known limitations. The current documentation task does not require starting services or fixing application code.

## Verification status

Inspection baseline: `f1d152d`, 2026-09-25.

| Check | Evidence / limitation |
| --- | --- |
| Compose configuration | `docker compose --env-file .env.example config --quiet` passed configuration validation. This does not prove that containers build or start. Compose reports the existing top-level `version` field as obsolete. |
| Backend Maven wrapper | Running `mvnw.cmd -v` failed because `.mvn/wrapper/maven-wrapper.properties` is missing under `backend/`; the tracked properties file is in `backend/wrapper/`. See [#26](https://github.com/studioforyou/zpi-studio/issues/26). |
| Flutter widget test | Static inspection found that the test expects the original counter UI, while the app displays a ping screen. Flutter tests were not run; the SDK was unavailable in the review environment's PATH. See [#27](https://github.com/studioforyou/zpi-studio/issues/27). |
| Backend test environment | `contextLoads` uses the application's datasource configuration. No isolated test database configuration is provided in the repository. |
| End-to-end startup | Not verified during this documentation review. |

Update this record with a commit and actual results when these checks are performed again. Do not turn the commands below into evidence of a successful run.

## PostgreSQL configuration and operation

The following commands describe the existing Docker Compose setup. Runtime startup has not been revalidated for this documentation change. They require Docker to be installed and running, with host port `5432` available.

From the repository root, create a local environment file **only if one does not already exist**:

PowerShell:

```powershell
Copy-Item .env.example .env
```

POSIX shell:

```sh
cp .env.example .env
```

Review `LOCAL_DB_USER` and `LOCAL_DB_PASSWORD` in `.env`. The file is ignored by Git. Example values are for local development.

To start only the database:

```sh
docker compose up -d postgres
```

The configured database is `zpi_studio`, exposed on port `5432`. Its data is stored in the `postgres_data` volume and persists across container restarts.

To connect inside the container, using the example username:

```sh
docker exec -it zpi_postgres psql -U zpi_user -d zpi_studio
```

If you changed `LOCAL_DB_USER`, replace `zpi_user` accordingly. With `psql` installed on the host:

```sh
psql -h localhost -p 5432 -U zpi_user -d zpi_studio
```

Use the password from `.env`. A PostgreSQL GUI client can use the same connection details.

To stop the Compose services without removing the named data volume:

```sh
docker compose down
```

## Backend and mobile

[docker-compose.yml](../docker-compose.yml) also defines a backend service, built from [backend/Dockerfile](../backend/Dockerfile), connected to PostgreSQL through Compose environment variables. The Dockerfile uses its own Maven installation and skips tests; it does not validate the local wrapper or the test suite.

The configured command for building and starting both services is:

```sh
docker compose up -d --build
```

This command has not been verified end to end in this review. See the [backend entry point](../backend/README.md) and the [mobile entry point](../mobile/README.md) before working on integration. The mobile backend address is currently hardcoded and must match the chosen device or emulator; no shared environment-selection mechanism is implemented.
