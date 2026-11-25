import 'package:flutter/painting.dart';

import 'client_theme_types.dart';

// Every value of the client app theme lives in this file: colours, text
// styles, lines, corners, gaps, component sizes and animation times.
//
// Change a value here and every client screen follows. Screens and widgets
// never hard-code these values; they read them through `context.ct`
// (see `client_theme.dart`).
//
// The values come from the accepted design (docs/redesign/client/), measured
// at a 390 × 844 viewport. Sizes are in logical pixels.

const clientThemeConfig = ClientThemeConfig(
  light: _light,
  dark: _dark,
  typography: _typography,
  shape: _shape,
  spacing: _spacing,
  sizes: _sizes,
  motion: _motion,
);

// ---------------------------------------------------------------------------
// Colours
// ---------------------------------------------------------------------------

const _light = ClientPalette(
  background: Color(0xFFFFFFFF),
  ink: Color(0xFF0F1D20),
  muted: Color(0xFF76878A),
  faint: Color(0xFFB3BEC0),
  line: Color(0xFFE4EAEA),
  surface: Color(0xFFF4F7F7),
  primary: Color(0xFF85C5C3),
  onPrimary: Color(0xFF0F1D20),
  primarySoft: Color(0xFFDCEEED),
  accent: Color(0xFF449C9E),
  highlight: Color(0xFF193A43),
  orange: Color(0xFFEF5B2E),
  orangeText: Color(0xFFB33A12),
  orangeTintOpacity: 0.10,
  ok: Color(0xFF2A8C55),
  scheduled: Color(0xFF2E8A90),
  todayFill: Color(0xFFD3DBDB),
  scrim: Color(0x6B0B1416), // #0B1416 at 42 %
  onBadge: Color(0xFFFFFFFF),
  ownMarker: Color(0xFF85C5C3),
  freeEntry: Color(0xFF85C5C3),
  chatAvatars: [Color(0xFF2E8A90), Color(0xFF193A43), Color(0xFF5B8E92)],
  onChatAvatar: Color(0xFFFFFFFF),
  switchKnobOn: Color(0xFF193A43),
  classFallback: Color(0xFF76878A),
  profileBanner: Color(0xFF449C9E),
  onProfileBanner: Color(0xE6FFFFFF), // white at 90 %
);

const _dark = ClientPalette(
  background: Color(0xFF0B1416),
  ink: Color(0xFFECF3F2),
  muted: Color(0xFF7E9396),
  faint: Color(0xFF3E5155),
  line: Color(0xFF1B292C),
  surface: Color(0xFF101C1F),
  primary: Color(0xFF193A43),
  onPrimary: Color(0xFFECF3F2),
  primarySoft: Color(0xFF132C31),
  accent: Color(0xFF6FC0C2),
  highlight: Color(0xFF85C5C3),
  orange: Color(0xFFFF7A4D),
  orangeText: Color(0xFFFF7A4D),
  orangeTintOpacity: 0.10,
  ok: Color(0xFF5CC98A),
  scheduled: Color(0xFF6FC0C2),
  todayFill: Color(0xFF2C3E42),
  scrim: Color(0x8C000000), // #000000 at 55 %
  onBadge: Color(0xFF0B1416),
  ownMarker: Color(0xFF2E8A90),
  freeEntry: Color(0xFF2E8A90),
  chatAvatars: [Color(0xFF2E8A90), Color(0xFF193A43), Color(0xFF5B8E92)],
  onChatAvatar: Color(0xFFFFFFFF),
  switchKnobOn: Color(0xFFECF3F2),
  classFallback: Color(0xFF7E9396),
  profileBanner: Color(0xFF2E8A90),
  onProfileBanner: Color(0xE6FFFFFF), // white at 90 %
);

// ---------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------
//
// ClientTextSpec(size, weight:, letterSpacingEm:, height:, upperCase:)
// Letter spacing is in em (−0.03 = −3 % of the size). Height is the line
// height as a multiple of the size.

const _typography = ClientTypography(
  fontFamily: 'FamiljenGrotesk',
  selectedWeight: FontWeight.w600,
  pageTitle: ClientTextSpec(
    27,
    weight: FontWeight.w600,
    letterSpacingEm: -0.03,
    height: 1.1,
  ),
  sheetTitle: ClientTextSpec(
    30,
    weight: FontWeight.w600,
    letterSpacingEm: -0.035,
    height: 1.05,
  ),
  sheetTitleSmall: ClientTextSpec(
    26,
    weight: FontWeight.w600,
    letterSpacingEm: -0.035,
    height: 1.1,
  ),
  hero: ClientTextSpec(
    96,
    weight: FontWeight.w600,
    letterSpacingEm: -0.055,
    height: 0.88,
  ),
  heroUnit: ClientTextSpec(20, weight: FontWeight.w500, height: 1.2),
  weekDaySelected: ClientTextSpec(
    46,
    weight: FontWeight.w700,
    letterSpacingEm: -0.03,
    height: 1,
  ),
  weekDay: ClientTextSpec(
    18,
    weight: FontWeight.w500,
    letterSpacingEm: -0.03,
    height: 1,
  ),
  stripDay: ClientTextSpec(
    22,
    weight: FontWeight.w500,
    letterSpacingEm: -0.03,
    height: 1.1,
  ),
  monthTitle: ClientTextSpec(
    24,
    weight: FontWeight.w600,
    letterSpacingEm: -0.03,
    height: 1.1,
  ),
  nextClass: ClientTextSpec(
    21,
    weight: FontWeight.w600,
    letterSpacingEm: -0.02,
    height: 1.15,
  ),
  when: ClientTextSpec(20, weight: FontWeight.w500, height: 1.25),
  eventTitle: ClientTextSpec(
    18,
    weight: FontWeight.w600,
    letterSpacingEm: -0.015,
    height: 1.15,
  ),
  eventTime: ClientTextSpec(17, weight: FontWeight.w600, height: 1.1),
  rowTitle: ClientTextSpec(
    16.5,
    weight: FontWeight.w600,
    letterSpacingEm: -0.01,
    height: 1.25,
  ),
  pickerTitle: ClientTextSpec(
    17,
    weight: FontWeight.w600,
    letterSpacingEm: -0.01,
    height: 1.25,
  ),
  consequenceTitle: ClientTextSpec(15.5, weight: FontWeight.w600),
  changeLine: ClientTextSpec(13, weight: FontWeight.w600),
  body: ClientTextSpec(15),
  tab: ClientTextSpec(15.5),
  segment: ClientTextSpec(14.5),
  button: ClientTextSpec(14, weight: FontWeight.w600, height: 1.1),
  buttonLarge: ClientTextSpec(16, weight: FontWeight.w600, height: 1.1),
  buttonGhost: ClientTextSpec(13.5, weight: FontWeight.w600, height: 1.1),
  meta: ClientTextSpec(13.5, height: 1.35),
  warning: ClientTextSpec(13.5, height: 1.45),
  strip: ClientTextSpec(13.5, weight: FontWeight.w500, height: 1.35),
  rowValue: ClientTextSpec(14),
  caption: ClientTextSpec(13),
  lineValue: ClientTextSpec(14.5),
  lineLabel: ClientTextSpec(12.5),
  label: ClientTextSpec(
    12,
    weight: FontWeight.w500,
    letterSpacingEm: 0.06,
    upperCase: true,
  ),
  status: ClientTextSpec(14, weight: FontWeight.w600),
  navLabel: ClientTextSpec(11, height: 1.1),
  badge: ClientTextSpec(10, weight: FontWeight.w600, height: 1),
  bigNumber: ClientTextSpec(34, weight: FontWeight.w600, height: 1),
  statNumber: ClientTextSpec(22, weight: FontWeight.w600, height: 1.1),
  historyDate: ClientTextSpec(18, weight: FontWeight.w600, height: 1.1),
  kicker: ClientTextSpec(12.5, weight: FontWeight.w600, height: 1.2),
  entryChange: ClientTextSpec(18, weight: FontWeight.w600, height: 1.2),
  offerPrice: ClientTextSpec(
    20,
    weight: FontWeight.w600,
    letterSpacingEm: -0.02,
    height: 1.1,
  ),
  checkoutPrice: ClientTextSpec(
    44,
    weight: FontWeight.w600,
    letterSpacingEm: -0.04,
    height: 1,
  ),
  profileMonogram: ClientTextSpec(
    64,
    weight: FontWeight.w600,
    letterSpacingEm: -0.02,
    height: 1,
  ),
  placesNumber: ClientTextSpec(
    20,
    weight: FontWeight.w700,
    letterSpacingEm: -0.02,
    height: 1,
  ),
  profileName: ClientTextSpec(
    36,
    weight: FontWeight.w600,
    letterSpacingEm: -0.035,
    height: 1.05,
  ),
  avatarInitials: ClientTextSpec(
    28,
    weight: FontWeight.w600,
    letterSpacingEm: -0.02,
    height: 1,
  ),
);

// ---------------------------------------------------------------------------
// Lines and corners
// ---------------------------------------------------------------------------

const _shape = ClientShape(
  radius: 0,
  hairline: 1,
  tabIndicator: 2,
  selectedDayUnderline: 4,
  monthSelectedUnderline: 3,
  focusOutline: 1,
  entryOutline: 1.5,
  checkboxBorder: 1.5,
);

// ---------------------------------------------------------------------------
// Gaps
// ---------------------------------------------------------------------------

const _spacing = ClientSpacing(
  page: 20,
  sheet: 20,
  sheetBottom: 34,
  rowVertical: 13,
  section: 14,
  xxs: 2,
  xs: 4,
  s: 8,
  m: 12,
  l: 16,
  xl: 24,
);

// ---------------------------------------------------------------------------
// Component sizes
// ---------------------------------------------------------------------------

const _sizes = ClientSizes(
  buttonHeight: 44,
  buttonSmallHeight: 36,
  buttonPrimaryHeight: 52,
  buttonPairWidth: 156,
  buttonPadding: 18,
  buttonSmallPadding: 13,
  buttonGhostHeight: 38,
  iconButton: 38,
  tapTarget: 44,
  icon: 22,
  headerHeight: 58,
  navBarHeight: 60,
  navIcon: 24,
  badge: 16,
  segmentHeight: 37,
  grabWidth: 36,
  grabHeight: 4,
  statusDot: 8,
  unreadDot: 7,
  classSquare: 7,
  timeColumn: 82,
  keyColumn: 88,
  switchWidth: 40,
  switchHeight: 24,
  switchKnob: 16,
  entryBarHeight: 8,
  entryBarGap: 4,
  ownMarkerWidth: 16,
  ownMarkerHeight: 5,
  changeDot: 4,
  avatar: 44,
  avatarSmall: 36,
  consequenceIcon: 30,
  consequenceGlyph: 16,
  profileAvatar: 72,
  textFieldMinHeight: 96,
  weekStripHeight: 84,
  monthRowHeight: 50,
  entryTileWidth: 16,
  entryTileHeight: 8,
  entryBarMaxSegments: 30,
  checkbox: 20,
  checkboxGlyph: 15,
  offerBuyWidth: 64,
  profileBanner: 200,
);

// ---------------------------------------------------------------------------
// Animation
// ---------------------------------------------------------------------------

const _motion = ClientMotion(
  short: Duration(milliseconds: 150),
  medium: Duration(milliseconds: 260),
);
