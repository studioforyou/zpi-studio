# studio4U web panel

Administrator web panel of studio4U, built with React, TypeScript, Vite and Mantine.

## Requirements

Node.js 24 (see `.nvmrc`; Vite needs `^20.19.0 || >=22.12.0`) and npm.

## Commands

```sh
npm install
npm run dev         # dev environment
npm run dev:staging # staging environment,
npm run lint        # oxlint
npm run typecheck
npm test            # Vitest
npm run build       # production bundle for staging
npm run format      # Prettier (format:check in CI)
```

## Environments

There are two environments, selected by the Vite mode
**dev** (backend on your machine) and **staging** (backend in the cloud). Each reads the committed
`.env.<mode>` file (Firebase web app config) and its own git-ignored `.env.<mode>.local` file; create
the local files once from the committed templates:

```sh
cp .env.example.dev .env.dev.local
cp .env.example.staging .env.staging.local
```

- `API_PROXY_TARGET`: where the local Vite dev server (`npm run dev`, `npm run dev:staging`)
  forwards `/api` and `/actuator`. Not used by `npm run build`.
- `VITE_FIREBASE_*` (in `.env.dev` and `.env.staging`): the Firebase web app of the environment's
  project, `studioforyou-dev` or `studioforyou-stage`. It must be the project whose tokens the
  backend accepts (`FIREBASE_PROJECT_ID`). 

The code reads the environment name as `env.appFlavor` (`dev` or `staging`); the "Status systemu"
page shows it. Requests always go to relative paths (`/api/...`) on the panel's own address, so a
deployed panel needs `/api` routed to the backend on the same domain.

In development the browser only talks to the Vite server, which forwards `/api` and `/actuator` to
the backend.

## Checking the backend connection

1. Start the backend (port 8080).
2. Run `npm run dev` and open http://localhost:5173.
3. Log in as an administrator (see "Authentication").
4. Open "Status systemu" and click "Sprawdź połączenie". It calls `GET /api/ping` for now and
   will switch to `GET /actuator/health` once the backend exposes it.

## Authentication

The panel signs in with Firebase Authentication (e-mail and password), then calls `GET /api/v1/me`.
Firebase only proves the identity; the account status and roles come from the backend. Only an
`ACTIVE` account with the `ADMIN` role can use the panel; any other account is signed out again with
a message on the login page.

- Every API request sends the Firebase ID token (`Authorization: Bearer <token>`); Firebase refreshes
  it on its own. A `401` response logs you out.
- Firebase keeps the user signed in across reloads and browser restarts; on start the panel checks
  the saved user with `GET /api/v1/me`.
- Test administrator: `beata.lis@seed.studioforyou.pl` from the backend seed
  (`backend/src/main/resources/db/seed`). It can log in only where a Firebase user with UID
  `seed-uid-beata-lis` exists. To log in as yourself, create a Firebase user and a local account with
  `scripts/db/create_dev_account.sql` and the role `ADMIN`.

## Structure

```
src/
  app/        composition: providers, router, layout, navigation
  features/   one folder per module (pages and module-specific code)
  shared/     code used by many modules: API client, config, Polish texts, UI
  test/       test setup and tests
```

| Feature         | Backend modules                                       |
| --------------- | ----------------------------------------------------- |
| `schedule`      | scheduling, group_training, personal_training (rooms) |
| `accounts`      | identity, customer, staff                             |
| `passes`        | membership, payments                                  |
| `trainings`     | personal_training, group_training                     |
| `communication` | communication                                         |
| `statistics`    | statistics                                            |
| `auth`          | identity                                              |

## Conventions

- Modules in `features/` never import from each other; only `app/` composes them. Shared code goes
  to `shared/`.
- All user-visible text lives in `src/shared/i18n/pl.ts` (the panel is Polish only).
- All backend access goes through `src/shared/api`. The panel never talks to the database or
  storage directly.
- The API contract is described in `API_CONVENTIONS.md` and `ERROR_CODES.md` (project Wiki).
