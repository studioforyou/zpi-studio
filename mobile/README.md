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

## About this app

Flutter app for gym/studio clients and trainers (Android and iOS only).

## Prerequisites

- Flutter 3.47.7 (stable, Dart 3.13.5). The repo has no version-pin file
  yet; use this version.
- Android Studio / Android SDK and/or Xcode for the platform you run.

## Setup and checks

```sh
flutter pub get
flutter analyze
flutter test
```

Localizations are generated from `lib/l10n/app_pl.arb` into
`lib/l10n/generated/` (git-ignored). `flutter pub get` regenerates them;
after editing the ARB file you can also run `flutter gen-l10n`.

## Running

There are two environments: **dev** (local backend on your machine) and
**staging**. There is no production environment yet.

Dev defaults to `http://10.0.2.2:8080` on the Android emulator and
`http://localhost:8080` elsewhere; staging requires `API_BASE_URL`:

```sh
flutter run -t lib/main.dart
flutter run -t lib/main.dart --dart-define=API_BASE_URL=http://192.168.1.10:8080
flutter run -t lib/main_staging.dart --dart-define=API_BASE_URL=https://...
```

These two Dart entry points are the only flavor mechanism; there are no native
Gradle/Xcode flavors.

## Structure

`lib/app` holds app-wide wiring (bootstrap, config, router, role shell,
theme); `lib/core` holds cross-cutting infrastructure (auth session and
controller, token storage, Dio client and interceptors); `lib/shared` holds
reusable widgets; `lib/l10n` holds the translations; and each business module
lives in `lib/features/<module>/` with `data/` (APIs, repositories, models)
and `presentation/` (screens, widgets).

Module names: accounts = Konta, passes = Karnety, trainings = Treningi,
schedule = Grafik, communication = Komunikacja, statistics = Statystyki.

## Language rule

Everything in the repo (code, identifiers, comments, docs) is in English.
The only Polish text is user-facing UI text, and it lives exclusively in
`lib/l10n/app_pl.arb`. Never hard-code UI strings in widgets.

## Roles

The mobile app has only the **client** and **trainer** roles; administrators
use the web panel. Parents are clients who are guardians of child customer
profiles (listed in `Session.profiles`), not a separate role.

## Data conventions

- Identifiers are backend UUIDs; keep them as `String` in Dart (no UUID package).
- The backend stores event timestamps in UTC (`TIMESTAMPTZ`). Planned wire format: ISO-8601 UTC strings, to be confirmed in #179. Parse with `DateTime.parse` and convert with `toLocal()` only at display time. Recurring rules and trainer availability are local studio time (weekday plus local time), not UTC.
- Never use `double` for money. The backend stores amounts as `NUMERIC(10,2)` plus a 3-letter currency code. The wire representation is decided in #179.
- `schedule_event`, `reservation` and `customer_pass` carry an integer `version` (optimistic locking). When editing these entities the client sends the version back; handling of a version conflict (planned: HTTP 409) is defined in #179.

## Feature to backend module mapping

| Flutter feature | Backend/database modules it consumes |
|---|---|
| `accounts` | identity (account, roles), customer (customer profile, guardianship), staff (staff profile) |
| `passes` | offering (pass products), membership (customer passes, makeup credits), payments (orders, payments) |
| `trainings` | booking (reservations, waiting list), group_training, personal_training, offering (service types) |
| `schedule` | scheduling (events, resources), staff (trainer availability, time off, substitutions), configuration (booking policies) |
| `communication` | communication (conversations, messages, notifications, device tokens) |
| `statistics` | no dedicated tables in the current schema |

Modules `documents`, `files`, `audit` and `integrations` have no Flutter feature yet; they are added when their stories are implemented.

## Known placeholders

- Auth is faked (`FakeAuthRepository`): any credentials log in as a demo user
  with both roles and two child profiles.
- No token refresh; a 401 response logs the user out.
- Theme seed color (`Colors.indigo`) until the Figma design tokens land (#5).
- Health check path `/actuator/health` is unconfirmed (#26).
- The tab sets per role are provisional.
- Active profile is `null` for accounts without a customer profile.
