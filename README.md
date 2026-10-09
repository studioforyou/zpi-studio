# ZPI Studio

A team project for a studio management application, with planned workflows for clients, trainers, and administrators. Product scope and domain terminology are being refined in the [backlog](https://github.com/studioforyou/zpi-studio/issues).

## Start here

**[Open the documentation map](docs/README.md)** for a visual overview, directory links, and reading paths.

| I want to... | Start with |
| --- | --- |
| Understand the current project stage | [Current state](#current-state) |
| Find a directory or project document | [Documentation map](docs/README.md) |
| Work on an issue or prepare a PR | [Workflow](docs/WORKFLOW.md) and [naming conventions](docs/NAMING_CONVENTIONS.md) |
| Check local setup and known limitations | [Local development](docs/LOCAL_DEVELOPMENT.md) |
| Use Codex or Claude with this repository | [AI context guide](docs/AI_CONTEXT.md) |
| Review architecture decisions | [Architecture decisions](docs/ARCHITECTURE_DECISIONS.md) |
| Find an unresolved decision | [Open questions](docs/OPEN_QUESTIONS.md) |

## Current state

Repository inspection baseline: `f1d152d`, reviewed on 2026-09-25. This documentation draft is tracked in [#42](https://github.com/studioforyou/zpi-studio/issues/42).

- **Present:** a Spring Boot backend with a single ping endpoint, a Flutter connection-check screen, and Docker Compose configuration for PostgreSQL and the backend.
- **Not implemented in the repository:** business features, a domain data model, Spring Modulith, GitHub Actions workflows, and staging deployment configuration.
- **Current work:** documentation and preparation. Application implementation is deferred to the course sprints; their detailed rules still await guidance from the instructors.
- **Planned separately:** a repository workflow assistant. [#43](https://github.com/studioforyou/zpi-studio/issues/43) covers its specification, not a running agent.

Configuration being present does not mean that the application has passed end-to-end verification. See [known setup and test limitations](docs/LOCAL_DEVELOPMENT.md#verification-status).

The [architecture decisions](docs/ARCHITECTURE_DECISIONS.md) collect the eleven ADRs for [#4](https://github.com/studioforyou/zpi-studio/issues/4) in a table of topics, decisions, and rationale. Backlog alignment and architecture review remain tracked in the [open questions](docs/OPEN_QUESTIONS.md).

Requirements and the domain glossary are still pending as repository documents. Their [backlog links](docs/README.md#pending-knowledge) remain the entry points. English is the agreed language for repository content; legacy application strings and comments have not been translated as part of this documentation task.

## Maintaining this entry point

Update this summary when a change affects the project stage or capabilities. Keep task status and assignments in GitHub; follow the [documentation maintenance rules](docs/README.md#keeping-documentation-current) for changes to project knowledge.
