# zpi-studio

```
zpi-studio
├── 1  Database (PostgreSQL in Docker Compose)
│   ├── 1.1  Setup and startup
│   └── 1.2  Connecting to the database
└── 2  Database migrations (Flyway)
    ├── 2.1  Where migrations live
    ├── 2.2  Naming convention
    ├── 2.3  Rules
    ├── 2.4  Checking the state
    └── 2.5  Resetting the local database
```

## 1 &nbsp; &nbsp; Database (PostgreSQL in Docker Compose)

### 1.1 &nbsp; &nbsp; Setup and startup

Docker must be installed and running (Docker Desktop on Windows/macOS).

1. Copy the example environment file:

```
cp .env.example .env
```

   and change `LOCAL_DB_USER` / `LOCAL_DB_PASSWORD` in it if needed (in Windows `cmd`, use `copy .env.example .env`).

2. Start the local database with one command:

```
docker compose up -d postgres
```

The database starts on port `5432` (database `zpi_studio`). Data is kept in the `postgres_data` volume, so it survives container restarts.

Stopping the database:

```
docker compose down
```

### 1.2 &nbsp; &nbsp; Connecting to the database

The backend talks to the database on its own, and the schema changes only through Flyway migrations (see below). A direct connection is for looking at the database: checking what a migration created, inspecting or fixing local data, and trying out SQL before it goes into a migration or a seed script. **Do not change the schema by hand**.

1. From the container (nothing to install):

```
docker exec -it zpi_postgres psql -U zpi_user -d zpi_studio
```

2. From the host (requires a local `psql` client):

```
psql -h localhost -p 5432 -U zpi_user -d zpi_studio
```

3. From a GUI client (IntelliJ IDEA Database tool, DBeaver, pgAdmin): host `localhost`, port `5432`, database `zpi_studio`, user and password as `LOCAL_DB_USER` / `LOCAL_DB_PASSWORD` in `.env`.

The commands above use the default user `zpi_user`; if you changed `LOCAL_DB_USER`, use your value. The password is `LOCAL_DB_PASSWORD` from `.env`.

## 2 &nbsp; &nbsp; Database migrations (Flyway)

The database schema is managed only by Flyway. The backend applies pending migrations automatically on startup, before Hibernate starts. Hibernate runs with `ddl-auto: validate`, so it never changes the schema; it only checks that entities match the tables.

### 2.1 &nbsp; &nbsp; Where migrations live

```
backend/src/main/resources/db/migration/
```

### 2.2 &nbsp; &nbsp; Naming convention

`V<version>__<description>.sql`, with **two** underscores between the version and the description, for example:

```
V1__init.sql
V2__create_core_schema.sql
V3__add_phone_to_account.sql
```

Versions are plain consecutive numbers. **Before adding a migration, check that the next number is not already taken on `main` or on another open branch.**

### 2.3 &nbsp; &nbsp; Rules

1. Every schema change is a new migration file. Never change the schema by hand or through Hibernate.
2. Never edit a migration that has already been applied to a shared environment, not even its comments: Flyway stores a checksum of every applied file and the backend will refuse to start. Fix mistakes with a new migration.
3. **All migrations are reviewed by the database owner (@krzysieknosek).**

### 2.4 &nbsp; &nbsp; Checking the state

```
docker exec -it zpi_postgres psql -U zpi_user -d zpi_studio -c "select version, description, success from flyway_schema_history"
```

### 2.5 &nbsp; &nbsp; Resetting the local database

If a local-only migration was changed, or the local database is in an unknown state, start from an empty database (this deletes all local data):

```
docker compose down -v
docker compose up -d --build
```
