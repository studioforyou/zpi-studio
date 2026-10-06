# Backend

Current state: a Spring Boot API skeleton using Java 21, with JPA and PostgreSQL dependencies. Production code contains the application entry point and `GET /api/ping`; business modules, entities, authentication, and database migrations are not implemented.

## Find the relevant source

- [Application entry point](src/main/java/studioforyou/pl/backend/BackendApplication.java)
- [Ping endpoint](src/main/java/studioforyou/pl/backend/PingController.java)
- [Build configuration](pom.xml)
- [Datasource configuration](src/main/resources/application.yml)
- [Existing context test](src/test/java/studioforyou/pl/backend/BackendApplicationTests.java)

Architecture decisions are tracked in [#25](https://github.com/studioforyou/zpi-studio/issues/25); the Spring Boot + Modulith skeleton in [#26](https://github.com/studioforyou/zpi-studio/issues/26). Spring Modulith is not currently a dependency. Existing libraries do not establish accepted ADRs by themselves.

Read [local development and verification limitations](../docs/LOCAL_DEVELOPMENT.md) before running or describing build/test commands. The Maven wrapper currently cannot find its configuration file. Return to the [documentation map](../docs/README.md) for team rules and domain sources.
