# Client redesign: components

This is the component set for the client app in the accepted style "Oś". Values come from the accepted prototype ([prototype.html](prototype.html)), measured at a 390 × 844 viewport.

Screens that only need the new style (login, password reset, account activation, splash, schedule filters, conversation, trainer picker, personal data, documents) get no separate mockups. They are built from these components.

## Principles

- One font, Familjen Grotesk (weights 400, 500, 600, 700). Hierarchy comes from size, not colour.
- Sharp corners everywhere: radius 0. Status dots are the only round shapes.
- No shadows, no elevation, no floating cards. Surfaces are separated by 1 px lines.
- Body text is never coloured. Teal and orange are used only where this document names them.
- One dominant element per screen.

## Tokens

Put these in `lib/app/theme/` as a `ThemeExtension` with a light and a dark instance. The extension replaces the seed-based `ColorScheme` in `app_theme.dart`.

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| `bg` | `#FFFFFF` | `#0B1416` | page, sheet and nav background |
| `ink` | `#0F1D20` | `#ECF3F2` | text, secondary button frame and label |
| `muted` | `#76878A` | `#7E9396` | secondary text, inactive tabs and nav items |
| `muted2` | `#B3BEC0` | `#3E5155` | past days, sheet grab handle, used-entry segments |
| `line` | `#E4EAEA` | `#1B292C` | every divider and outline |
| `surface` | `#F4F7F7` | `#101C1F` | waiting-list box, avatar placeholder |
| `primary` | `#85C5C3` | `#193A43` | primary button, next-class bar, free-entry segments, switch on |
| `onPrimary` | `#0F1D20` | `#ECF3F2` | text on `primary` |
| `primarySoft` | `#DCEEED` | `#132C31` | selected row in a picker sheet, event chat avatar |
| `accentTeal` | `#449C9E` | `#6FC0C2` | selected bottom-nav icon and label |
| `highlight` | `#193A43` | `#85C5C3` | "Zaraz po Twoich zajęciach" hint |
| `orange` | `#EF5B2E` | `#FF7A4D` | today, changes, badges, ending-pass tag |
| `orangeText` | `#B33A12` | `#FF7A4D` | text inside orange-tinted strips |
| `orangeTint` | `orange` at 10 % | `orange` at 10 % | background of make-up and offer strips |
| `ok` | `#2A8C55` | `#5CC98A` | "Aktywny" status |
| `todayFill` | `#D3DBDB` | `#2C3E42` | today's cell in the month grid |
| `scrim` | `#0B1416` at 42 % | `#000000` at 55 % | behind sheets |

Class-type colours come from `calendarColor` in the API. When it is null, fall back to `muted`.

## Typography

| Style | Size / weight | Letter spacing | Where |
| --- | --- | --- | --- |
| `pageTitle` | 27 / 600 | −0.03 em | tab header ("Zajęcia", "Karnety") |
| `sheetTitle` | 30 / 600, line height 1.05 | −0.035 em | class sheet title |
| `sheetTitleSmall` | 26 / 600 | −0.035 em | picker and make-up sheets |
| `heroNumber` | 96 / 600, line height 0.88 | −0.055 em | remaining entries on a pass |
| `weekDaySelected` | 46 / 700 | −0.03 em | selected day in the week strip |
| `weekDay` | 18 / 500 | −0.03 em | other days in the strip and month |
| `nextClass` | 21 / 600 | −0.02 em | next-class bar title, pass picker name |
| `when` | 20 / 500 | 0 | date and time under a sheet title |
| `eventTitle` | 18 / 600 | −0.015 em | class name in the day list |
| `eventTime` | 17 / 600 | 0 | start time in the day list |
| `rowTitle` | 16.5 / 600 | −0.01 em | list rows |
| `body` | 15 / 400 | 0 | default |
| `tab` | 15.5 / 400, selected 600 | 0 | Moje karnety / Kup karnet |
| `segment` | 14.5 / 400, selected 600 | 0 | Moje / Wszystkie |
| `button` | 14 / 600 (sheet primary 16 / 600) | 0 | buttons |
| `meta` | 13.5 / 400 | 0 | row subtitles, warnings, strips |
| `caption` | 13 / 400 | 0 | key-value labels, sheet kicker |
| `label` | 12 / 500, upper case | 0.06 em | section labels in Profil settings |
| `navLabel` | 11 / 400, selected 600 | 0 | bottom navigation |

## Spacing

- Page side padding: 20. Sheet side padding: 20, bottom 34.
- Rows: 13 vertical padding with a 1 px top line. The last row also has a bottom line.
- Sections: 14 between a strip or picker and the content below it.
- Touch targets: at least 44 × 44, including icon buttons (38 × 38 icon box inside a 44 target).

## Components

### Header (`PageHeader`)

Title (`pageTitle`) on the left, with icon buttons on the right: the history icon in Zajęcia and the bell in every tab. The bell carries the unread count badge (see Badge). Height 58, top padding 12.

### Bottom navigation (`AppNavBar`)

Four equal items: Karnety, Zajęcia, Wiadomości, Profil. Height 82, including 22 for the home indicator. Top line 1 px `line`. Item label `navLabel` in `muted`; the selected item has its icon and label in `accentTeal` and the label at weight 600. There is no indicator pill and no enlarged item. Badges sit on the icon.

### Badge

A square with min-width 15–16 and height 15–16, `orange` background, 10 / 600 number. The number is white in light theme and `bg` in dark theme. Round count badges are not used.

### Buttons (`AppButton`)

| Variant | Look | Height |
| --- | --- | --- |
| Primary | `primary` fill, `onPrimary` label 16 / 600. Only one per screen or sheet: "Zapisz się", "Potwierdź miejsce", "Dołącz do listy rezerwowej". Full width in sheets. | 52 |
| Secondary | 1 px `ink` frame, `ink` label 14 / 600, transparent background. "Historia wejść", "Kup kolejny", "Zachowaj trening", "Wyloguj się", "Napisz do trenera". Pairs sit side by side at 156 wide, centred. | 36 (44 standalone) |
| Ghost | no frame, `muted` label 13.5 / 500. A low-priority action next to a secondary button. | 38 |
| Destructive | secondary layout with the label in `orange` ("Wypisz się"). | 44 |

Disabled buttons use `muted2` for the frame and label.

### Tabs (`AppTabs`)

Full-width equal tabs under the header ("Moje karnety | Kup karnet"). Selected: `ink`, weight 600, with a 2 px `ink` underline on the 1 px `line` bottom border. Unselected: `muted`.

### Segmented switch (`AppSegmented`)

Two equal halves inside a 1 px `line` frame, height 37 ("Moje | Wszystkie"). The selected half has a 1 px `ink` outline and weight 600. There is no fill. A filter icon button sits to the right.

### Week strip and month (`CalendarStrip`)

The week strip is the collapsed month: dragging the chevron expands it to the full month grid and collapses it back. It has `<` and `>` arrows and keeps the weekday when the week changes.

- Days without own classes: `muted2`. Days with own classes: `muted`. The selected day uses `weekDaySelected` in `ink`.
- Markers under a day: a 16 × 5 `primary` bar for an own class (dark: `#2E8A90`), and a 4 × 4 `orange` dot for a change.
- In the month grid, the selected day has a 3 px `ink` underline and today has a `todayFill` square.

The prototype is the reference for gestures and animation.

### Day list (`EventRow`)

A three-column grid: time (82 wide, right-aligned, `eventTime` plus end time in `caption`), a 1 px vertical `line` axis with a 7 × 7 class-colour square, then the title (`eventTitle`) and meta (`meta`, `muted`). The right column shows free places, or "lista · n." on the waiting list.

- Free time between classes is shown as a small gap label.
- In Wszystkie, classes the person has not booked are dimmed to `muted`.
- A change line under the title uses `orange` at 13 / 600 ("Zwolniło się miejsce · potwierdź do 16:54", "Nowa godzina · zwykle 18:00").
- Cancelled classes: `muted2` with a strike-through.
- Day headers: `caption`, `muted2` for past days, "dziś" in `orange`.

### Next-class bar (`NextClassBar`)

A full-width `primary` bar under the segmented switch with padding 12/20/14. Line 1 reads "Dziś 17:00 · za 36 min" in `body` at 85 % opacity; line 2 is the class name in `nextClass`. A chevron sits on the right. Tapping it opens the class sheet.

### Bottom sheet (`AppSheet`)

`bg` with a 1 px top `line` and no radius, over `scrim`. A 36 × 4 grab handle in `muted2`. A kicker row (`caption`, `muted`) carries the close icon on the right. Then the title (`sheetTitle`) and `when`.

Building blocks inside sheets:

- **Key-value list:** 88-wide `caption` label and `body` value, with 1 px lines between rows ("Prowadzi", "Sala", "Miejsca").
- **Line:** a label in `caption` above a value in 14.5, with an optional "Zmień" link on the right.
- **Warning:** `meta` in `muted`, line height 1.45. This is plain text, not a box.
- **Hint:** `meta` in `highlight`, weight 500.
- **Change strip:** `orangeTint` background, `orangeText` 13.5 / 500, padding 10/12.
- **Consequence list:** 30 × 30 framed icons with a title (15.5 / 600) and a subtitle (`meta`). The positive item has a `primary` fill.
- **Pass choice (radio):** stacked 1 px framed options. The selected option has an `ink` frame.
- **Waiting-list box:** `surface` background with a 1 px `line` frame and the position number at 34 / 600.
- **Text field:** a 1 px `ink` frame, minimum height 96.

Sheet types in the prototype:

- class: book, booked, full, waiting list, offer;
- personal training: upcoming, cancel consequences, completed, dispute;
- make-up list, pass picker, person picker, entry history, logout confirmation.

### Picker sheet (`PickerSheet`)

Opened from the pass and person pickers. The kicker reads "Moje karnety"; the title is "Wybierz karnet" or "Czyje karnety?". Rows have 13 / 12 padding and 1 px lines. The selected row has a `primarySoft` background and a tick on the right. Person rows start with a 36 × 36 initial square in `primary`.

### Picker field (`PickerField`)

A 1 px `line` frame with padding 11/12/11/14. It shows the name in `nextClass`, a subtitle in `caption`, a status, and a chevron. The person picker sits above the pass picker and appears only for accounts with more than one profile.

### Status (`StatusDot`)

An 8 px round dot in the text colour, then the label at 14 / 600: Aktywny (`ok`), Zaplanowany (`#2E8A90`, dark `#6FC0C2`), Czeka na płatność or Zawieszony (`orange`), Wygasł or Anulowany (`muted`).

### Info strip (`InfoStrip`)

Full-width with no side margin and a 14 gap above it. Background `orangeTint`. Text `orangeText` at 13.5 / 500, on one line. An underlined, frameless action ("Dowiedz się więcej", 13.5 / 600) sits on the right. It is used for make-up credits above the pickers in Karnety.

### Pass card (`PassCard`)

- A line saying which classes the pass covers (`meta`).
- "Pozostało", then `heroNumber`, then "z 8 wejść" (20 / 500, `muted`).
- An entry bar: 8 px high segments with a 4 px gap. Used segments are `muted2` and remaining segments are `primary`. The prototype also shows a framed state for reserved entries, which is skipped because the API has no reserved count.
- Validity text ("ważny do 6.12 · pozostało 13 dni"). For an ending pass, a small orange-framed tag "Karnet niedługo się kończy" sits above it.
- A pair of secondary buttons: "Historia wejść", and "Kup kolejny" only when `expiringSoon` is true.
- An expandable "Wygasłe (n)" row underneath.

### List row (`AppRow`)

Title (`rowTitle`), optional subtitle (`meta`, `muted`), optional value on the right (`muted`), and a chevron in `muted2`. An unread row has a 7 × 7 `orange` square after the title.

### Switch (`AppSwitch`)

40 × 24, square. Off: 1 px `line` frame with a 16 px `muted2` knob on the left. On: `primary` fill with the knob in `highlight` on the right; in dark theme the knob is `ink`.

### Avatars

Chat avatars are 44 × 44 squares:

- private chats: three teal shades `#2E8A90`, `#193A43`, `#5B8E92` with white initials;
- group chats: `primary`;
- event chats: `primarySoft` with `ink` initials.

The profile avatar in Profil is a large square of initials that fades to the right.

### Theme switch

Profil has a sun / moon switch that stores the chosen mode. The default follows the system.

## Flutter notes

- Bundle Familjen Grotesk as an asset (`pubspec.yaml` `fonts:`) so the app does not depend on Google Fonts at runtime.
- Put shared widgets in `lib/shared/widgets/` and the tokens and text styles in `lib/app/theme/`. Screens must not use raw `Color(...)` values.
- Set `splashFactory` to `NoSplash` or a subtle highlight, and set elevation to 0 on `AppBar`, `BottomSheet` and `Card`. Use `RoundedRectangleBorder(borderRadius: BorderRadius.zero)` in the theme.
- Build bottom sheets with `showModalBottomSheet(isScrollControlled: true, barrierColor: scrim, shape: const RoundedRectangleBorder())`.
