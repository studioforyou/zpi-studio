# Seed accounts (dev / stage)

Test accounts created by the seed in this directory. Loaded on dev and stage only, never on prod.

| E-mail | Name | Role | Account status | Can log in | Notes |
| --- | --- | --- | --- | --- | --- |
| `anna.kowalska@seed.studioforyou.pl` | Anna Kowalska | TRAINER | ACTIVE | yes | Studio owner, extra permissions (manage schedule, health data, pass adjustments) |
| `marek.nowak@seed.studioforyou.pl` | Marek Nowak | TRAINER | ACTIVE | yes | Calisthenics + personal trainer of Tomasz |
| `ewa.wisniewska@seed.studioforyou.pl` | Ewa Wiśniewska | TRAINER | ACTIVE | yes | Pole dance and stretching, personal trainer of Katarzyna |
| `katarzyna.zielinska@seed.studioforyou.pl` | Katarzyna Zielińska | CLIENT | ACTIVE | yes | Richest data: pole dance pass, bookings, make-up credit, personal training |
| `tomasz.lewandowski@seed.studioforyou.pl` | Tomasz Lewandowski | CLIENT | ACTIVE | yes | Personal training package, open dispute about a session, health notes |
| `monika.dabrowska@seed.studioforyou.pl` | Monika Dąbrowska | CLIENT | ACTIVE | yes | **Parent** of Zosia (manages her bookings and consents), suspended time pass |
| `piotr.wojcik@seed.studioforyou.pl` | Piotr Wójcik | CLIENT | PENDING_APPROVAL | yes* | Self-registration waiting for approval, unpaid order |
| `julia.kaminska@seed.studioforyou.pl` | Julia Kamińska | CLIENT | INVITED | no | Invited by the studio, has not logged in yet (no Firebase user) |
| `adam.mazur@seed.studioforyou.pl` | Adam Mazur | CLIENT | SUSPENDED | yes* | Suspended for an overdue payment |
| `beata.lis@seed.studioforyou.pl` | Beata Lis | ADMIN | ACTIVE | yes | Studio administrator, the only seed account that can log in to the web panel |
| `karolina.szymanska@seed.studioforyou.pl` | Karolina Szymańska | TRAINER, CLIENT | ACTIVE | yes** | Trainer (kids pole dance) and client at the same time, **parent** of Kuba: tests role switching |

\* Logs in to Firebase, but the backend gives access to studio features only to ACTIVE accounts.

\*\* Needs a Firebase user with UID `seed-uid-karolina-szymanska`, like the other seed accounts.

The timetable showcase seed also completes the known cancellation terms of the original Katarzyna and Zosia reservation fixtures. With a fresh seed, Katarzyna can cancel and rebook the upcoming Tuesday class; Monika can act for Zosia on the kids class. Other historical reservations without known snapshots remain readable but cannot be cancelled through this workflow. Events retain the dates from the week the seed first ran.

Customer profiles without an account:

| Name | Notes |
| --- | --- |
| Zosia Dąbrowska | **Child** profile (born 2016), guardian: Monika Dąbrowska; kids pole dance group |
| Kuba Szymański | **Child** profile (born 2017), guardian: Karolina Szymańska |
| Krzysztof Wrona | Adult added by the studio without an account, archived |

## Timetable showcase (seed 08)

Adds **52 public classes across two upcoming weeks**, starting on the next Monday in Europe/Warsaw when first applied. Existing fixture dates, reservations and pass balances are preserved. Restarting the backend does not move these dates or restore cancelled bookings.

The seed changes data only. Fixture values use query-scoped CTEs without creating tables or other database objects.

- Monday 17:00 and Friday 17:00: three simultaneous classes in separate rooms (pole dance, calisthenics, stretching). Monday also has a partial overlap and short consecutive classes.
- All seven weekdays have classes; lengths range from 30 to 75 minutes. Most classes remain empty with this small client sample. Occupancy is always the count of actual active reservations. The original two-place Thursday pole class remains the full-group example (Katarzyna and Monika).
- No accounts, customer profiles or staff profiles are created. Participants use the existing Katarzyna, Monika and Tomasz accounts; Zosia uses her existing child profile through Monika.
- Katarzyna and Monika each have **two** additional reservations: first Monday 17:00 pole dance and second Wednesday 18:15 stretching. Both accounts see the same roster. Monika uses her existing Open pass; Katarzyna has a separate gifted Open pass, preserving her purchased pass's balance.
- Tomasz has **two** additional calisthenics reservations (first Tuesday 10:00 and second Friday 17:00) funded by a gifted Open pass. His original Monday class is linked to that pass so cancellation and rebooking work as well.
- Zosia has **one** additional Wednesday 15:30 booking in the first week, funded by a separate four-entry child pass with three entries left and a matching ledger. Her original Saturday class remains, so the two sessions fall on different days.
- Anna's four second-week pole classes move to Thursday, Friday and Saturday to respect her existing leave. Existing instructors cover the entire offer. Stretching is a separate green group service; workshops retain their original scope.

Use the same Firebase seed logins and passwords. No Firebase accounts or passwords are created or modified. This repeatable migration runs automatically with the dev/stage seed profile, including against an existing local database. It only inserts missing fixtures. For a date refresh on an old database, use a separate fresh development database rather than overwriting tester data.

This feature adds one seed file: `R__seed_08_timetable_showcase.sql`. The earlier local prototype and its one-off cleanup are not part of the shared seed. The showcase creates no obsolete participant or substitute accounts.

## Waiting list (seed 04)

The full two-place Thursday pole class (`19000000-…-0003`, Katarzyna and Monika) has two waiting participants: Tomasz first, then Julia. Neither gets a freed place yet: Tomasz has not accepted the required pole dance consent, and Julia's entry was added by Anna's account, which does not act for her. To try the automatic promotion, sign in as Tomasz, open the class (he sees his place on the list and the missing document), accept the consent in **Dokumenty i zgody**, then cancel Katarzyna's or Monika's reservation: Tomasz gets the place with his Open pass. Seed entries have no chosen pass, so the promotion uses the first eligible one. The workshop (`19000000-…-0009`) has an event-level policy with a 30-minute confirmation time, so a place freed there is offered and has to be confirmed.

## Make-up credits (seed 09)

Monika's original manually granted available credit incorrectly pointed at her Open pass, whose product has no make-up policy. `R__seed_09_makeup_credits.sql` adds a separate expired four-entry Pole Dance pass with its activation ledger and relinks only that known, unused seed credit. The source product has a make-up policy; the credit can remain valid after the source pass expires.

This is a scoped repair of the original fixture, rather than a change to granting rules. Existing credit dates, status and usage are preserved. Used credits and credits already linked elsewhere are left alone. The new pass is not a purchase, and Monika's existing Open pass, its balance and reservations are untouched. Re-running the seed neither renews credit validity nor adds duplicates. No schema or Firebase account changes are involved.
