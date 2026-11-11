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
  backend accepts (`FIREBASE_PROJECT_ID`). The values are public, so they are committed, like the
  mobile app's `firebase_options_<env>.dart`.

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

| Feature         | Backend modules                                |
| --------------- | ---------------------------------------------- |
| `schedule`      | scheduling (schedule, rooms), staff, trainings |
| `accounts`      | identity, customer, staff                      |
| `passes`        | membership, payments                           |
| `trainings`     | personal_training, group_training              |
| `communication` | communication                                  |
| `statistics`    | reporting                                      |
| `auth`          | identity                                       |

## Schedule

"Grafik" shows every event of the studio (classes, personal training, workshops, consultations and
blocks) in a day, week, month or list view, always in the studio's time zone from
`GET /api/v1/studio`. Filters run in the browser on the loaded period: text, kind, staff, room,
service, category, level, group, status, visibility, recurrence, weekdays, time of day, age group
and missing staff or room.

"Dodaj wydarzenie" (or a click on an empty slot) plans a one-off or a recurring event with its
staff and rooms. The second step shows every date with the conflicts and warnings the
backend found; dates of a recurring event can be left out before saving. An event's details offer
"Zmień obsadę i salę" for one event or the following ones of its series.

### Booking participants on behalf of clients

Until a scheduled group class (public or private) starts, its details show **Uczestnicy**
(`GET /api/v1/schedule-events/{eventId}/reservations`, #110) instead of the attendance: the booked
and free places, the waiting list, and for each participant the pass or _bez karnetu_, who booked
the place and the administrator's reason. **Zapisz uczestnika** searches participants by name,
phone or e-mail and checks them (`GET .../booking-options`): the booking window does not apply
to administrators, the participant's eligible passes are offered, and a full class or an unmet
requirement (age, documents, a missing pass, a service out of the offer) is listed and has to be
confirmed with **Rozumiem i zapisuję uczestnika mimo to**. Without an eligible pass the place is
booked without a pass entry. If the issues changed meanwhile (`409 BOOKING_OVERRIDE_REQUIRED`),
they are reloaded and have to be confirmed again. **Wypisz** cancels a place: the choice
between returning the entry and forfeiting it starts at what the cancellation terms give, also
when the participant could no longer cancel in the app. Both take an optional reason of up to
500 characters, kept in the history.

### Attendance corrections

The details of every event except a block list its participants under **Uczestnicy i
obecność**, a scheduled group class once it has started
(`GET /api/v1/schedule-events/{eventId}/attendance`, #111): each participant's attendance (present, absent or not marked), who recorded it and when, and the history of every
change with its author and reason. Once the event has started, **Koryguj** sets present or absent
with an optional reason of up to 500 characters
(`POST .../reservations/{reservationId}/correct-attendance`), also after the trainers' own time to
mark attendance has ended. A correction never changes the pass balance: the entry is settled when
attendance is first recorded. If the attendance changed meanwhile, the list is reloaded and the
correction has to be saved again.

## Participant pass corrections

In **Karnety → Karnety uczestników**, search for a participant and choose **Koryguj wejścia**
in the matching pass row with a finite entry count. Corrections sit alongside extension and suspension
actions, and the response retains the pass's suspension details and effective status.
Enter the intended remaining balance in one field,
optionally give a reason, and compare the current and intended counts before confirming. A green
or red badge shows the signed difference; an unchanged count is neutral and cannot be submitted. The panel
calculates the signed adjustment from the displayed current balance; setting it to zero is
allowed, while leaving it unchanged does not create a correction. Each correction is recorded with its
author, time and reason in the pass ledger. The original entry allowance and validity stay
unchanged; a correction can increase the balance beyond the original allowance.

Corrections require administrator access. If the pass changed while the form was open,
refresh its balance and enter the intended correction again. An interrupted response can
also mean that the correction was saved: refresh and check the balance before trying again.

## Statistics

"Statystyki" is the studio's dashboard. One filter bar above all reports chooses the period (7, 30
or 90 days, this month, this year or a custom range of up to 731 days) and the events: their type
(group classes, workshops, personal training; by default the first two, so personal training does
not skew them), kind of classes, instructor, fixed group and room. The filters and the open tab
live in the address, e.g. `?tab=outcomes&period=thisMonth&kind=CLASS&staff=<id>`, so a view can be
linked and survives a reload. Each tab loads only its own reports; the page and its chart library
(`@mantine/charts`, built on Recharts) are loaded only when the page is first opened.

- **Przegląd**: the headline numbers of every report (occupancy, attendance, no-shows, late
  cancellations, active and new customers), both trends, and the fullest and emptiest classes,
  each with a link to its details.
- **Frekwencja i obłożenie** (#164, `GET /api/v1/reports/class-attendance`): group classes and
  workshops that already started and were not cancelled. Average occupancy (booked places of the
  classes with a limit against their places), attendance (attended against booked), average
  attendees per class and the waiting list, then occupancy and attendance week by week, a heatmap
  of occupancy with weekdays in columns and start hours in rows, as in the schedule, and a table of
  classes (a recurring series is one class) that can be summed per kind of classes or fixed group
  and sorted by any column. Personal training has no occupancy, so with only that type chosen the
  tab says so instead.
- **Nieobecności i anulowania** (#166, `GET /api/v1/reports/booking-outcomes`): no-shows, late and
  in-time cancellations as numbers and shares of the bookings, what became of the bookings,
  no-shows and late cancellations week by week, how long before the start places were cancelled,
  and each kind of classes with its current cancellation rules.
- **Klienci** (#163, `GET /api/v1/reports/customer-activity`): active and new customers against
  the period of the same length right before. It covers the whole studio: only the period applies,
  and the tab says so while event filters are chosen.

A participant whose attendance nobody marked counts as present, as when the trainer completes the
class; the reports also show how many such places there are. Chart colours live in
`features/statistics/chartColors.ts`: they are checked together for colour vision deficiencies, and
every chart has a legend and a table or tooltip with the same numbers.

To add a report: put its value in `statisticsTabs.ts`, its label in `pl.statistics.tabs` and its
panel in `panels` in `StatisticsPage.tsx`. A panel gets the page's `StatisticsFilters`; a report
about events sends `filters.query` with `eventReportParams` (the backend's `ReportQuery` takes the
same parameters), and `ReportFrame`, `ChartCard` and `StatTile` give it the loading states and
look of the other reports.

## Conventions

- Modules in `features/` never import from each other; only `app/` composes them. Shared code goes
  to `shared/`.
- All user-visible text lives in `src/shared/i18n/pl.ts` (the panel is Polish only).
- All backend access goes through `src/shared/api`. The panel never talks to the database or
  storage directly.
- The API contract is described in `API_CONVENTIONS.md` and `ERROR_CODES.md` (project Wiki).
