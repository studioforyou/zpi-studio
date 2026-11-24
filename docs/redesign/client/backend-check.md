# Client redesign: backend check

This document compares the accepted client prototype ([prototype.html](prototype.html)) with the API on `main` as of 24.11.2026 (`678328e`). Each flow lists the endpoints it uses and anything the API is missing.

Result: almost every new flow already has an API. One closed story needs a small API extension (make-up credit source, #72). Two open stories need new backend work (#147, #121).

## Flows with a complete API

| Flow (story) | Endpoints | Fields the UI relies on |
| --- | --- | --- |
| Class calendar, Moje / Wszystkie (#87, #115, #118, #119) | `GET /me/profiles/{profileId}/group-classes`, `GET /me/profiles/{profileId}/reservations`, `GET /me/profiles/{profileId}/personal-trainings` | `CustomerGroupClass.capacity`, `availablePlaces`, `ownReservation`, `waitlist.ownEntry`, `staff`, `resources`, `calendarColor` |
| One-off changes in the list (#120, #144) | same as above, plus `GET /me/profiles/{profileId}/schedule-changes` | `CustomerGroupClass.seriesChange` ("Nowa godzina · zwykle 18:00") |
| Parent sees own and child schedule (#116) | the calendar endpoints called once per profile from `GET /me` | none |
| Booking with pass choice (#88) | `POST /me/profiles/{profileId}/group-classes/{eventId}/reservations` | `booking.eligiblePasses[]` (`productName`, `entriesRemaining`, `validUntil`). The default pass (the one that ends first) is chosen in the app by sorting on `validUntil`. |
| Cancel with consequences (#89, #244) | `POST /me/profiles/{profileId}/reservations/{reservationId}/cancel` | `ownReservation.cancellation.entryReturned`, `passConsequence`, `freeCancelUntil` |
| Waiting list (#94) | `POST …/group-classes/{eventId}/waitlist-entries`, `POST …/waitlist-entries/{entryId}/cancel` | `waitlist.allowed`, `waitingCount`, `confirmationMinutes`, `ownEntry.position` |
| Freed place offer (#145) | `POST …/waitlist-entries/{entryId}/confirm`, `…/cancel`; notification `WAITLIST_PLACE_OFFERED` | `ownEntry.status = OFFERED`, `ownEntry.offerExpiresAt`; notification `data.eventId`, `data.offerExpiresAt` |
| Personal training: cancel (#95) | `GET`, `POST /me/profiles/{profileId}/personal-trainings/{reservationId}/cancel` | `cancellation.freeCancelUntil`, `late`, `effect`, `validityDays`, `passName` |
| Personal training: dispute (#98) | `POST …/personal-trainings/{reservationId}/disputes` | `disputeAvailability.closesAt`, `dispute.status` |
| Make-up booking (#96) | `GET`, `POST /me/profiles/{profileId}/makeup-credits/{creditId}/group-classes` | none |
| Training history (#97) | `GET /me/profiles/{profileId}/training-history` (window of at most 93 days), `GET /me/pass-entry-history` | `TrainingHistoryEntry.outcome`. The month summary (number of classes, attendance) is counted in the app. |
| Passes and child passes (#70, #75) | `GET /me/profiles/{profileId}/passes` for each profile | `OwnPass.entriesRemaining`, `entriesTotal`, `validUntil`, `status`, `expiringSoon` |
| Entry history sheet (#71) | `GET /me/profiles/{profileId}/passes/{passId}/entry-history` | none |
| "Kup kolejny" for an ending pass (#73) | `POST /me/profiles/{profileId}/pass-orders` with `startAfterPassId` | The button is shown when `OwnPass.expiringSoon` is true. The backend rule (7 days or 2 entries) matches the prototype. |
| Pass offer and checkout (#69) | `GET /pass-products`, `POST …/pass-orders`, `POST /me/pass-orders/{orderId}/mock-payment` | none |
| Classes a pass covers ("Pole dance: początkujący i średniozaawansowany") | `GET /pass-products` and `GET /service-types`, joined on `OwnPass.variantId` | This already works in the current app (`passes_api.dart`). |
| Notifications inbox from the bell (#142, #144–#146) | `GET /me/notifications`, `POST …/read`, `POST …/read-all`, `GET …/unread-count` | `type`, `data` (deep link), `readAt` |
| Messages (#141, #148) | `/me/conversations…` | none |
| Profile, documents, related profiles (#49–#51) | `GET /me`, `/me/customer-profiles/{profileId}`, `/me/profiles/{profileId}/documents` | none |

## Gaps

### 1. Make-up credit does not say where it came from (#72, closed)

The make-up sheet shows the class that was missed, for example "Pole dance początkujący · Wtorek 1.12, 18:00 · odwołane przez studio". `OwnMakeupCredit` returns only `sourcePassId`, `productName`, `grantedAt` and `expiresAt`.

The data already exists: `makeup_credit.source_reservation_id` points to the reservation and its event. Proposed change: add a nullable `source` object to `OwnMakeupCredit` with `eventId`, `title`, `startsAt` and `reason` (`STUDIO_CANCELLED`, `CANCELLED_IN_TIME`, `GRANTED_BY_STAFF`).

Until this lands, the sheet shows the pass name and the expiry date only.

### 2. Notification preferences (#147, open)

There is no API for a client's notification preferences. `NotificationType` (`/notification-types`) is the studio-wide admin setting, not per user. The settings screen (H1 in the prototype) needs:

- per-profile switches for each notification category: reminder, changes and cancellations, waiting list, pass end, messages, studio announcements;
- the dispatcher honouring those switches before sending a push.

Marketing consents already exist as documents (`MARKETING_EMAIL`, `MARKETING_SMS`). The settings screen should read and write them through the documents API, not through a new endpoint.

### 3. Phone calendar sync (#121, open)

No backend exists yet. The "Kalendarz w telefonie" switch in Profil stays hidden until the story is designed and built.

## Not needed

These prototype elements have no story, so they are skipped in implementation and need no API:

- "Zawieś" on a pass;
- "Trenerzy prowadzący" in Profil;
- "Wyświetl odwołane zajęcia" in the make-up sheet;
- the legend splitting used and reserved entries (the pass card shows remaining entries only, as #70 requires).

## Notes for the app

- No endpoint lists all own waiting-list entries. The Moje view takes them from `waitlist.ownEntry` in `GET …/group-classes` for the visible range. Moje needs that call anyway to show classes with an offered place.
- Opening a notification uses `data.eventId`. For `WAITLIST_PLACE_OFFERED`, the app opens the class sheet in the offer state.
