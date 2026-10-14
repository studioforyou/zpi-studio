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


