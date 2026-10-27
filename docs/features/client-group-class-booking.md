# Client group class browsing and booking

Implements #87, #88, #89 and #118 as one client participation workflow. The feature is based on main containing the service and booking policy configuration from #108, merged in PR #216 (`ac773de`).

## User experience

The mobile **Oferta** tab displays public group classes, and **Mój grafik** displays only the selected participant's upcoming bookings. Both screens directly reuse the trainer's `ScheduleCalendar`: day, week and month views share period navigation and the action returning to today. Parents select their own or a child's participant profile.

The day view uses an hourly timeline with parallel columns for overlapping sessions. The week view groups client booking cards by day in time order. The month view marks class dates with colored dots and lists client booking cards for the selected day. Cards and the legend use each service's configured calendar color; cancelled classes are marked separately. Occupancy is presented as occupied places out of capacity, with a progress indicator and an explicit full state.

Filters follow the trainer calendar's bottom-sheet and chip style. Both client screens can filter by service and instructor and control cancelled-class visibility; Oferta also offers an available-places filter. Pull-to-refresh updates the displayed period. Switching participants rebuilds the calendar with that participant's data and resets its navigation and filters.

The calendar header and events scroll together, keeping the controls accessible with enlarged text and limited screen height. This also prevents layout overflow when a retained calendar is resized while the keyboard is open on another tab.

After booking, class details show a **Uczestnicy** section with the currently booked participants' display names and a marker for the selected participant. Names are loaded separately and are available only while the selected authorized profile has an active reservation; the offer does not include a public roster. Contact details, other participant identifiers and pass information are never returned by this roster.

Class details explain why an action is unavailable. Booking requires an explicit eligible pass choice; a single eligible pass is preselected in the confirmation. Cancellation displays its deadline and entry-return consequence before confirmation. Successful actions refresh the offer, own reservations and pass balances. Errors refresh current state and never automatically repeat a newly punitive cancellation.

Cancellation details show the free deadline; the confirmation gives a short entry consequence, and the success message simply confirms cancellation. Booking confirmation mentions a one-entry debit only for a selected entry-based pass.

The feature adds one development seed, `R__seed_08_timetable_showcase.sql`, with 52 classes over two upcoming weeks, including three-way overlaps and short adjacent classes. It reuses the existing accounts: two additional reservations each for Katarzyna, Monika and Tomasz, and one for Zosia. Shared classes expose the same roster to each booked account; occupancy comes from real reservation rows. Existing full small-group fixtures remain. The same seed completes known cancellation terms for original booking fixtures. See the [seed guide](../../backend/src/main/resources/db/seed/README.md) for dates, funding and rerun behavior.

## HTTP contract

All paths below are relative to `/api/v1/me/profiles/{profileId}`. The authenticated active account must act for that active participant. An unrelated profile or reservation returns `404`; the actor comes from the verified token. Lists use `{ "items": [...] }`, instants use UTC and windows are half-open `[from, to)`, at most 93 days.

| Operation | Method and path | Result |
| --- | --- | --- |
| Browse public group classes | `GET /group-classes?from=...&to=...` | Class details, live occupancy, own reservation and booking eligibility |
| Read a class | `GET /group-classes/{eventId}` | The same class projection |
| Read co-participants | `GET /group-classes/{eventId}/participants` | `{items: [{displayName, isSelf}]}`, only for a profile with an active reservation; otherwise `403` |
| Upcoming own reservations | `GET /reservations?from=...&to=...` | The same projection, limited to own active future reservations |
| Read a reservation | `GET /reservations/{reservationId}` | Reservation and current cancellation availability |
| Book | `POST /group-classes/{eventId}/reservations` with `{ "customerPassId": "uuid" }` | `201` and `Location`, or `200` for an already active booking |
| Cancel | `POST /reservations/{reservationId}/cancel` with `{ "expectedVersion": 0, "expectedEntryReturned": true }` | Updated reservation, with the actual outcome |

The generated `/v3/api-docs` specification describes the response fields. Class responses include the service's nullable `calendarColor` (`#RRGGBB`). Unlimited capacity and unlimited pass entries use `null`; zero available places means full.

## Rules and transactions

- Only public `CLASS` occurrences with a service definition enter this offer. Private sessions, personal training, workshops, consultations and technical blocks are outside this use case.
- A public class can be booked without fixed-group membership. Group context selects more specific policy settings; membership remains relevant to future automatic enrolment. No closed-group rule is configured by the current schema.
- Booking checks occurrence status, start time, opening/closing windows, capacity, active service, age on the occurrence date, published required blocking document acceptances, and a suitable pass.
- Passes must belong to the participant, have `ACTIVE` or `SCHEDULED` status, cover the service and occurrence date, be outside a noncancelled suspension, and have an available entry or an unlimited balance. Both validity dates must be present. Archiving a product or price variant does not invalidate an already assigned pass.
- Policies resolve field by field through `STUDIO`, `SERVICE_TYPE`, `GROUP`, then `EVENT`. Booking opens an elapsed number of days before the start, inclusively, and closes an elapsed number of minutes before the start, exclusively. The free cancellation deadline is inclusive; cancellation at or after the event starts is unavailable.
- Reservation cancellation terms and deadline are captured at booking; later configuration changes do not change them.
- The transaction locks the occurrence, then reservation, then pass. Reservation and ledger writes commit together. Last-place and last-entry checks are repeated under their owner locks. An existing active reservation is returned on retry without charging again.
- Timely cancellation returns the allocated entry. Late cancellation follows the captured permission and forfeiture settings. A changed entry-return consequence returns `409 CANCELLATION_CONSEQUENCE_CHANGED`; a stale reservation version returns `409 VERSION_CONFLICT`. The client refreshes and requires confirmation again.
- Existing rows without historical cancellation snapshots remain readable but return `CANCELLATION_TERMS_UNAVAILABLE` for self-service cancellation. Migration V7 does not guess historical terms. New reservations always capture the complete cancellation snapshot.
- The timetable showcase seed supplies the known terms for three original entry-backed Katarzyna/Zosia fixtures only, enabling their cancel/rebook acceptance flow without changing real historical rows.

## Module ownership

`booking` owns reservation reads and writes and coordinates the transaction. `scheduling.GroupClassSchedule` supplies safe occurrence values and the compatible occurrence lock. `membership.BookingEntitlements` owns pass checks, allocations, returns and ledger entries; it uses `offering.PassVariantLookup.coversService` for the product-service relationship. `documents.DocumentRequirements` owns required acceptance checks. `configuration.BookingPolicies.forOccurrence` supplies scoped policy values. `customer.CustomerProfiles` authorizes the participant. Cross-module contracts exchange IDs and immutable values; persistence queries stay inside their owner.

On mobile, `features/schedule/` owns `ClientScheduleScreen`, the client schedule adapter and filters, the hourly `GroupClassTimeline`, and the shared `ScheduleCalendar` in `presentation/widgets/schedule_calendar.dart`. Client cards and the service-color legend plug into the same calendar used by trainers. `features/trainings/` retains the booking API and `GroupClassDetailsScreen`; its `presentation/widgets/` directory contains the detail summary, occupancy and participant-list widgets. Booking and pass rules remain in their existing API flow.

The local [Wiki update proposal](../wiki-proposals/87-client-group-class-booking.md) records the required module-map change and contract documentation. Publishing Wiki changes is a separate remote action.

## Manual acceptance

1. Run the local backend with migrations V6/V7 and start the mobile app against it. Use an active client with a valid pass and the required document acceptances. Existing seeded schedules are relative to the week when the database was first seeded; create a future public group class in the admin panel if those dates have passed.
2. In **Oferta**, check day, week and month navigation and returning to today. Verify time, instructor, room, service color, legend and occupancy against the admin event. In day view, check parallel overlapping classes; in week and month views, check the client booking cards. Check service/instructor/availability filters, cancelled-class visibility and refresh. Open details, select a pass and confirm the booking. Check the same calendar views in **Mój grafik** and the pass's remaining entries.
3. Return to the class and cancel before its free deadline. Check that the place and pass entry return. Repeat booking/cancellation to check that displayed state stays current.
4. For a class inside the free-cancellation deadline, check that confirmation states whether the entry will be forfeited. A forbidden cancellation stays disabled with an explanation.
5. As a parent, switch to a child and repeat with a child-eligible class and pass. Check that the parent's data and actions do not remain in the child's view.
6. On two accounts, attempt the last place simultaneously. Only one booking should succeed; the losing account should see refreshed state. Also test no eligible pass, expired/suspended pass dates, missing required acceptances, a full class, failed network fetch and retry.
7. Open a booked class and check the participant list and self marker. Cancel the booking: the roster must no longer be shown. An unrelated profile must not be able to request names directly. Check the same behavior when acting for a child.

Waitlists (#94), the complete cancellation preview use case (#95), make-up bookings (#96), personal training, attendance and administrative participant operations retain their separate scopes.
