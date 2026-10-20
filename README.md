# ZPI Studio

Studio management application with a shared backend API, a mobile client, and an administration web client.

## Repository and documentation

| Area | Responsibility |
| --- | --- |
| [Wiki](https://github.com/studioforyou/zpi-studio/wiki) | Architecture, API, infrastructure, database instructions, and assistant guidance |
| [backend/](backend/README.md) | Backend API and business logic |
| [mobile/](mobile/README.md) | Flutter application for clients and trainers |
| [web/](web/README.md) | Web application for studio administration |
| [infrastructure/](infrastructure/README.md) | Cloud infrastructure and environment configuration |
| [scripts/](scripts/README.md) | Shared development and maintenance helpers |
| [docs/](docs/README.md) | Contribution agreements and documentation links |

## Start working

- [Backend modules, table ownership, and expected collaboration](https://github.com/studioforyou/zpi-studio/wiki/Backend-modules)
- [Database setup, connections, and migrations](https://github.com/studioforyou/zpi-studio/wiki/Database-instructions)
- [Backend setup](backend/README.md#local-development)
- [Mobile setup](mobile/README.md#running)
- [Contribution agreements](docs/README.md)
- [Shared working rules](https://github.com/studioforyou/zpi-studio/wiki/Shared-context)
- [Assistant context guide](https://github.com/studioforyou/zpi-studio/wiki/AI-context)

Before changing backend features or persistence, read the backend module map in the Wiki. If a change affects module responsibilities, table ownership, or collaboration between modules, update that page as part of the same work and link the relevant Wiki revision in the pull request. Define concrete module contracts with the use case that needs them.
