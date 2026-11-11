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

The screens use the profile-scoped group-class booking API. They support choosing an eligible pass, booking, cancellation with a short entry consequence before confirmation, and viewing co-participants while booked. Data is scoped to both account and selected participant; switching to a child cannot reuse another participant's action. Successful mutations refresh class availability, own reservations and pass balances. A place the studio booked for the participant (#110) appears among their reservations, also for a private class; one booked without a pass says so (*Studio zapisało to miejsce bez karnetu.*) and its cancellation has no entry consequence (`cancellation.entryReturned` is `null` and is sent back as such).

Waiting list (#94): on a full class the details screen offers **Dołącz do listy rezerwowej** instead of the disabled **Zapisz się**, with the same pass choice as booking (nothing is charged on joining) or the reason why joining is not possible. A waiting participant sees their position (`Pozycja na liście: 2 z 3`), a warning when a freed place would currently skip them (`promotionBlockedReason`, with the documents shortcut when needed) and **Opuść listę rezerwową** behind a confirmation. When a place frees up, the server books the first eligible waiting participant automatically, so the class then simply shows **Masz rezerwację**. Calendar tiles of upcoming classes (day, week and month views) mark the place with an hourglass (`Lista rezerwowa · 2`). The screen reads the class's `waitlist` field and uses `POST .../group-classes/{eventId}/waitlist-entries` and `POST .../waitlist-entries/{id}/cancel`; when the waiting list is disabled for the class, or a server does not send the field, a full class keeps the old disabled **Zapisz się**.

When the class's booking policy requires confirming a freed place (`waitlist.confirmationMinutes > 0`, set in the admin panel as *Wymagaj potwierdzenia zwolnionego miejsca*), the texts say so, and a participant whose place is held sees **Zwolniło się miejsce dla Ciebie** with the deadline (`offerExpiresAt`), **Potwierdź miejsce** (`POST .../waitlist-entries/{id}/confirm`) and **Zrezygnuj z miejsca** (the same `/cancel`, which passes the place on). An expired or currently blocked offer cannot be confirmed; tiles mark a held place as *Miejsce do potwierdzenia*.

The history icon in **Mój grafik** opens the training history (`/client/reservations/history`, #97): the selected participant's past bookings month by month, with attendance, cancellations and a monthly attendance rate.



## Trainer class details

Tapping an event in the trainer's **Grafik** opens `TrainerEventDetailsScreen`
(`lib/features/trainings/presentation/`): the time, rooms, lead and assistants,
occupancy against capacity, and the participants with an active reservation,
each with a child marker and their age on the day of the class. The event and
its participants are cached per account, like the trainer's schedule.

The participants come from the event's attendance sheet
(`GET /api/v1/me/schedule-events/{eventId}/attendance`, #102). From the event's
start until 12 hours after its end, the lead and assistants mark each
participant present or absent (`PATCH .../attendance`) and can change it
again; **Oznacz zajęcia jako odbyte** (`POST .../complete`) records everyone
still unmarked as present and marks the event as held. After that time the
marks are read only and an administrator corrects them in the web panel.

Until a scheduled group class starts, the **+** next to *Uczestnicy* (#106)
opens `AddClassParticipantsScreen`: the trainer searches active clients
(`GET /api/v1/customers`) and books them one after another; clients already on
the class are marked *Już zapisany*. The trainer books on the rules of the
client's own booking. Tapping a client loads
`GET /api/v1/me/schedule-events/{eventId}/booking-options?customerId=` in a
bottom sheet. A client who could not book themselves (booking window, a full
class, age, documents, no eligible pass, …) gets the reason and no booking
button. Otherwise the sheet shows the pass to charge (a single eligible pass is
chosen automatically), a note when the client is on the waiting list, and an
optional note for the history. **Zapisz na zajęcia** sends
`POST .../reservations`; a rule broken meanwhile, e.g. the class filled up,
loads the conditions again and shows why. A booking refreshes the class's
participants.

## Push notifications and inbox

Push notifications use Firebase Cloud Messaging (`firebase_messaging`).
`lib/core/push/` wraps FCM (`PushMessaging`, replaced by `NoPushMessaging`
when Firebase is not initialized, e.g. in widget tests) and registers the
device (`PushDevice`): after sign-in the app asks for permission and sends the
FCM token to `POST /api/v1/me/devices`, again when FCM replaces the token, and
removes it with `DELETE /api/v1/me/devices/{token}` before sign-out. A refused
permission or a missing token only means the notifications stay in the inbox.

`lib/app/push/push_notifications.dart` connects pushes with the app. Tapping a
class reminder opens **Mój grafik** of the participant it is about, switching
to the child's profile if needed; other notifications open the notifications
screen.
The notification is marked read. A message that arrives while the app is open
is shown as a snack bar and refreshes the inbox.

The bell in the top right corner of the **Wiadomości** tab opens the
notifications screen (`/client/messages/notifications`,
`/trainer/messages/notifications`): the inbox of the active role
(`GET /api/v1/me/notifications?role=...`) with a top right action that marks
everything read. The unread count is a badge on the bell and on the tab.

### iOS

The Dart code is ready for iOS, but iOS pushes need a paid Apple Developer
account, which the project does not have yet. Until then `token()` returns
`null` on iOS (no APNs token) and the app silently skips registration. Once
the account exists:

1. Create an APNs authentication key (Certificates, Identifiers & Profiles →
   Keys, with Apple Push Notifications service) and upload it in Firebase
   console → Project settings → Cloud Messaging → Apple app configuration, for
   each Firebase project (dev and stage).
2. In Xcode (`ios/Runner.xcworkspace`, target Runner → Signing & Capabilities)
   add **Push Notifications** and **Background Modes** with **Remote
   notifications**. This creates `Runner.entitlements` with `aps-environment`.
3. Make sure the App ID `pl.studioforyou.mobile` has Push Notifications
   enabled.

No Dart change is needed. On the iOS Simulator (Xcode 14+ on Apple silicon or
a T2 Mac), pushes also need the steps above; to try only the tap handling, drag
`ios/simulator_push_sample.apns` onto the simulator (put a real `profileId`
in it first).
