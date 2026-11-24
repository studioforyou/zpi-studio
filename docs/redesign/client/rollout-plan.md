# Client redesign: rollout plan

This plan sets the order in which the accepted client design ([prototype.html](prototype.html)) is implemented in the Flutter app (`mobile/`).

- Parent issue: #281.
- Components: [components.md](components.md).
- Backend status: [backend-check.md](backend-check.md).

## Principles

- **Separate PRs.** Each step below is one pull request to `main`, so it gets its own review and can be squashed without the others.
- **No feature flags.** The client app changes tab by tab, and nothing switches back to the old design.
- **Shared foundation first.** The theme and shared widgets come before any screen, so screens never carry their own colours or sizes.
- **Client role only.** Trainer screens are not redesigned yet; see the open question at the end.
- **Existing stories only.** Elements without a story are not built: "Zawieś", "Trenerzy prowadzący", "Wyświetl odwołane zajęcia", and the used / reserved legend split.

## Steps

### 1. Foundation

- Familjen Grotesk bundled as a font asset.
- Theme tokens for light and dark (`ThemeExtension`).
- Text styles.
- Zero-radius, zero-elevation Material theme.
- Theme mode stored per device. It defaults to the system mode; the switch itself is added to Profil in step 6.
- Shared widgets: `AppButton`, `AppTabs`, `AppSegmented`, `AppRow`, `AppSwitch`, `StatusDot`, `Badge`, `InfoStrip`, `AppSheet` with its building blocks, `PickerSheet`, `PickerField`, `PageHeader`.
- A widget gallery test (golden or widget tests) for each component in both themes.

Screens: none change visibly yet, apart from the font and colours.

### 2. Shell and navigation

Current client tabs: Oferta (`ScheduleScreen`), Karnety (`PassesScreen`), Mój grafik (`ReservationsScreen`), Wiadomości, Profil.

New client tabs: Karnety, Zajęcia, Wiadomości, Profil. The app opens on Zajęcia.

- `AppNavBar` replaces the Material navigation bar for the client role.
- Every client tab gets a `PageHeader` with the bell. The notifications route moves from under Wiadomości to a route reachable from any tab.
- `ScheduleScreen` and `ReservationsScreen` stay reachable under Zajęcia until step 3 replaces them. This keeps the PR small.

### 3. Zajęcia (largest step)

Stories: #87, #88, #89, #94, #96, #115, #116, #118, #119, #120, #144, #145, #244.

- One calendar replaces Oferta and Mój grafik:
  - week strip that expands into the month;
  - Moje / Wszystkie;
  - person filter for parents (Ja / child / Wszyscy);
  - continuous day list;
  - next-class bar.
- Filters sheet under the filter icon (restyle of `client_schedule_filter.dart`).
- Class sheet replaces `GroupClassDetailsScreen`:
  - booking with pass choice;
  - booked state with cancel consequences;
  - full class with waiting list;
  - freed-place offer;
  - studio-cancelled state with the make-up action.
- Make-up mode in the calendar (#96) replaces `makeup_booking_screen.dart`.

This step can be split into two PRs: calendar and list, then the class sheet.

### 4. Personal training and history

Stories: #95, #97, #98.

- Personal-training sheet: upcoming, cancel consequences, completed, dispute. It replaces `personal_training_screen.dart` for clients.
- Training history as a separate screen behind the history icon in the Zajęcia header. It has a month view, a summary and the tabs Zajęcia / Wejścia, and replaces `training_history_screen.dart`.

### 5. Karnety

Stories: #69, #70, #71, #72, #73, #75.

- Moje karnety:
  - person picker (only for accounts with more than one profile);
  - pass picker sheet;
  - pass card with remaining entries;
  - "Historia wejść" sheet (replaces `pass_usage_screen.dart`);
  - "Wygasłe";
  - "Kup kolejny" for an ending pass.
- Make-up strip and make-up sheet. These replace the "Do odrobienia" tab (`makeup_credits_tab.dart`).
- Kup karnet tab: offer as one box per product, checkout restyle (`pass_checkout_screen.dart`). "Kup kolejny" opens checkout with `startAfterPassId`.
- If the make-up source API (backend gap 1) is not merged yet, the make-up sheet shows only the pass and the expiry date.

### 6. Wiadomości, Powiadomienia, Profil and sign-in

Stories: #49, #50, #51, #141, #142, #146, #148.

- Wiadomości:
  - Prywatne / Grupowe filters;
  - rows with square avatars;
  - "Napisz do trenera" on the right half;
  - conversation and trainer picker restyle.
- Powiadomienia screen opened from the bell, with all notification types listed in the inventory.
- Profil:
  - name with the square avatar;
  - rows;
  - theme switch;
  - "Zmień profil" (only with more than one profile);
  - "Wyloguj się" with a confirmation sheet;
  - personal data and documents restyle.
- Sign-in flow restyle: splash, login, password reset, invitation, role picker.

### 7. States and polish

- Empty, loading and connection-error states on every client screen.
- Dark theme pass on real devices.
- Accessibility: text scaling to 130 % without clipping, and contrast checks.
- Update widget tests that assert old texts or layouts. 53 test files exist under `mobile/test/`; run each step's affected tests in its own PR.

### Later, after the backend work

| Story | Depends on |
| --- | --- |
| #147 notification settings in Profil | backend gap 2 (preferences API) |
| #121 phone calendar switch | backend gap 3 |
| #74, #143, #149 | stories are still open and have no design yet |

## Open question

Should the trainer screens pick up the new font and colours in step 1 while keeping their current layouts?

The recommended answer is no: apply the new theme only inside the client shell, and leave the trainer app unchanged until its own redesign. A half-restyled trainer app would look inconsistent, and keeping its theme avoids breaking trainer widget tests.
