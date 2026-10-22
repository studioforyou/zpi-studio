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

\* Logs in to Firebase, but the backend gives access to studio features only to ACTIVE accounts.

Customer profiles without an account:

| Name | Notes |
| --- | --- |
| Zosia Dąbrowska | **Child** profile (born 2016), guardian: Monika Dąbrowska; kids pole dance group |
| Krzysztof Wrona | Adult added by the studio without an account, archived |
