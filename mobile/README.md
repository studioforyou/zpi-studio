# mobile

A new Flutter project.

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Learn Flutter](https://docs.flutter.dev/get-started/learn-flutter)
- [Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Flutter learning resources](https://docs.flutter.dev/reference/learning-resources)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.


# Mobile app setup

## Prerequisites

- Flutter 3.47.7 (stable, Dart 3.13.5). 

## Setup and checks

```sh
flutter pub get
flutter analyze
flutter test
```

Localizations are generated from `lib/l10n/app_pl.arb` into
`lib/l10n/generated/` (git-ignored). `flutter pub get` regenerates them;.

## Running

There are two environments: **dev** (local backend on your machine) and
**staging**. 

Both run from the single entry point `lib/main.dart`; the environment comes
from a JSON file in `env/` passed with `--dart-define-from-file`:

`env/dev.json` and `env/staging.json` are git-ignored. Create them once from
the committed templates and adjust them locally:


- `env/dev.json`: set `API_BASE_URL` to your computer's IP for a physical
  device, or to an empty string to use the default (`http://10.0.2.2:8080`
  on the Android emulator, `http://localhost:8080` elsewhere).
- `env/staging.json`: `APP_FLAVOR` is `staging`; `API_BASE_URL` is required.

```sh
flutter run --dart-define-from-file=env/dev.json
flutter run --dart-define-from-file=env/staging.json
```

To check the connection to the backend, tap the ping icon on the login
screen (`GET /api/ping` against the configured `API_BASE_URL`).

## Structure

`lib/app` holds app-wide wiring (bootstrap, config, router, role shell,
theme); `lib/core` holds cross-cutting infrastructure (auth session and
controller, token storage, Dio client and interceptors); `lib/shared` holds
reusable widgets; `lib/l10n` holds the translations; and each business module
lives in `lib/features/<module>/` with `data/` (APIs, repositories, models)
and `presentation/` (screens, widgets).


## Roles

The mobile app has only the **client** and **trainer** roles; administrators
use the web panel. Parents are clients who are guardians of child customer
profiles (listed in `Session.profiles`), not a separate role.


## Feature to backend module mapping

| Flutter feature | Backend/database modules it consumes |
|---|---|
| `accounts` | identity (account, roles), customer (customer profile, guardianship), staff (staff profile) |
| `passes` | offering (pass products), membership (customer passes, makeup credits), payments (orders, payments) |
| `trainings` | booking (reservations, waiting list), group_training, personal_training, offering (service types) |
| `schedule` | scheduling (events, resources), staff (trainer availability, time off, substitutions), configuration (booking policies) |
| `communication` | communication (conversations, messages, notifications, device tokens) |
| `statistics` | no dedicated tables in the current schema |

## Available make-up credits

The client **Karnety → Do odrobienia** tab shows a compact count with the selected participant's name. Each credit puts its exact expiry first (converted from UTC to the device's local time), with the source pass name underneath. The blue information button opens the explanation in a bottom sheet. Soonest expiry comes first. It supports pagination, pull-to-refresh, empty/error states and retry. Responses and page selection are separated by account and participant; returning to the app or reaching a displayed credit's expiry refreshes the list.

The screen reads `GET /api/v1/me/profiles/{profileId}/makeup-credits`. Only unused, unexpired credits count. Booking a make-up class is a separate workflow. For dev/stage preview, Monika Dąbrowska has an available seed credit from an expired four-entry Pole Dance pass, while Katarzyna Zielińska has used/expired examples that should not appear. Existing seed dates stay fixed when the backend restarts.

## Client group classes

The client **Oferta** and **Mój grafik** tabs directly reuse the trainer's `ScheduleCalendar` with day, week and month navigation. Oferta shows public group classes; Mój grafik shows the selected participant's upcoming bookings. The day view uses an hourly timeline with parallel columns for overlapping classes. Week and month views use client booking cards, service colors and a legend. Filters follow the trainer calendar's bottom-sheet and chip style.

Calendar screens, adapters, filters and the timeline live in `lib/features/schedule/`. Booking details and reusable booking widgets live in `lib/features/trainings/presentation/`, with widgets in its `widgets/` directory. The feature is based on main containing #108 (PR #216, `ac773de`).

The screens use the profile-scoped group-class booking API. They support choosing an eligible pass, booking, cancellation with a short entry consequence before confirmation, and viewing co-participants while booked. Data is scoped to both account and selected participant; switching to a child cannot reuse another participant's action. Successful mutations refresh class availability, own reservations and pass balances.

Waiting list (#94): on a full class the details screen offers **Dołącz do listy rezerwowej** instead of the disabled **Zapisz się**, with the same pass choice as booking (nothing is charged on joining) or the reason why joining is not possible. A waiting participant sees their position (`Pozycja na liście: 2 z 3`), a warning when a freed place would currently skip them (`promotionBlockedReason`, with the documents shortcut when needed) and **Opuść listę rezerwową** behind a confirmation. When a place frees up, the server books the first eligible waiting participant automatically, so the class then simply shows **Masz rezerwację**. Calendar tiles of upcoming classes (day, week and month views) mark the place with an hourglass (`Lista rezerwowa · 2`). The screen reads the class's `waitlist` field and uses `POST .../group-classes/{eventId}/waitlist-entries` and `POST .../waitlist-entries/{id}/cancel`; when the waiting list is disabled for the class, or a server does not send the field, a full class keeps the old disabled **Zapisz się**.

When the class's booking policy requires confirming a freed place (`waitlist.confirmationMinutes > 0`, set in the admin panel as *Wymagaj potwierdzenia zwolnionego miejsca*), the texts say so, and a participant whose place is held sees **Zwolniło się miejsce dla Ciebie** with the deadline (`offerExpiresAt`), **Potwierdź miejsce** (`POST .../waitlist-entries/{id}/confirm`) and **Zrezygnuj z miejsca** (the same `/cancel`, which passes the place on). An expired or currently blocked offer cannot be confirmed; tiles mark a held place as *Miejsce do potwierdzenia*.

The history icon in **Mój grafik** opens the training history (`/client/reservations/history`, #97): the selected participant's past bookings month by month, with attendance, cancellations and a monthly attendance rate.



## Trainer class details

Tapping an event in the trainer's **Grafik** opens `TrainerEventDetailsScreen`
(`lib/features/trainings/presentation/`): the time, rooms, lead and assistants,
occupancy against capacity, and the participants with an active reservation,
each with a child marker and their age on the day of the class. The event and
its participants are cached per account, like the trainer's schedule.
