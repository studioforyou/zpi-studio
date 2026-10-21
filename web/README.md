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
**dev** (backend on your machine) and **staging** (backend in the cloud). Each reads its own
git-ignored file; create them once from the committed templates:

```sh
cp .env.example.dev .env.dev.local
cp .env.example.staging .env.staging.local
```

- `API_PROXY_TARGET`: where the local Vite dev server (`npm run dev`, `npm run dev:staging`)
  forwards `/api` and `/actuator`. Not used by `npm run build`.

The code reads the environment name as `env.appFlavor` (`dev` or `staging`); the "Status systemu"
page shows it. Requests always go to relative paths (`/api/...`) on the panel's own address, so a
deployed panel needs `/api` routed to the backend on the same domain.

In development the browser only talks to the Vite server, which forwards `/api` and `/actuator` to
the backend.

## Checking the backend connection

1. Start the backend (port 8080).
2. Run `npm run dev` and open http://localhost:5173.
3. Log in with any e-mail and password.
4. Open "Status systemu" and click "Sprawdź połączenie". It calls `GET /api/ping` for now and
   will switch to `GET /actuator/health` once the backend exposes it.

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

## Known placeholders

- Login is simulated (`FakeAuthService`): any e-mail and password work.
- Tokens are kept in memory only; reloading the page logs you out.
- No token refresh: a `401` response logs you out.
- The panel does not check the ADMIN role; every signed-in user can manage rooms ("Sale").
- Service types cannot be created yet, so a room's allowed services can only be chosen once the
  database has service types.
