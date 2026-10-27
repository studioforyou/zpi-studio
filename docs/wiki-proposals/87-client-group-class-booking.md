# Proposed Wiki update: client group class booking

Prepared locally for #87, #88, #89 and #118. No remote Wiki page is changed by this file.

The feature is based on main containing #108, merged in PR #216 (`ac773de`).

## Backend-modules

Source: <https://github.com/studioforyou/zpi-studio/wiki/Backend-modules>

In section 4, add `offering` to the `booking` collaborators, with the purpose **service status and participation age limits**. Other existing collaborators and table ownership remain unchanged. Link the booking entry to a dedicated **Client-group-class-booking** page.

## New page: Client-group-class-booking

Publish the contract and rule sections from [the local feature guide](../features/client-group-class-booking.md), linking to the owning Java contracts and generated OpenAPI specification:

- `scheduling.GroupClassSchedule`: public class projection and occurrence lock, no participation-table reads.
- `customer.CustomerProfiles.displayNames`: batched display names for an already authorized set of participant IDs. The booking-owned roster endpoint authorizes an active reservation and returns only names and a self marker, never other participant identifiers or contact data.
- `membership.BookingEntitlements`: eligible passes, atomic reserve/release and ledger idempotency; no dependency on booking.
- `offering.PassVariantLookup.coversService`: coverage for held passes, including archived catalogue entries.
- `documents.DocumentRequirements.hasMissing`: required published blocking documents, audience/service scope, revocations and reacceptance.
- `configuration.BookingPolicies.forOccurrence`: field-level studio/service/group/event inheritance.

Document occurrence/reservation/pass lock order, atomic ledger updates, policy snapshots, the consequence-confirmation guard and the legacy-reservation limitation. State that public class booking does not itself require fixed-group membership. V7 adds four booking-owned columns to the existing reservation table; the 67-table/18-module count does not change.

The class projection includes the real service calendar color. Mobile navigation distinguishes **Oferta** (public group classes) from **Mój grafik** (the selected participant's upcoming bookings). Both directly reuse the trainer's `ScheduleCalendar` day/week/month navigator and follow its filter style. Day view uses an hourly timeline with parallel overlapping classes; week and month views use client booking cards. Service colors, a legend and occupancy indicators carry through all views. Calendar code lives in `features/schedule/`; booking details and their `presentation/widgets/` live in `features/trainings/`.

The participant-list access scope is event-specific: actor authorization for the selected profile and its active reservation are both required.

The exploratory Module-contracts/Module-workflows pages remain proposals; this use case defines its own implemented contract. The complete cancellation preview/token workflow from #95 is separate from the entry-return guard used here.
