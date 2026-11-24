import 'package:flutter/painting.dart';

// The shapes of the values in `client_theme_config.dart`. Change the values
// there; change this file only to add a new kind of value.

/// Colours of one brightness (light or dark) of the client app.
class ClientPalette {
  const ClientPalette({
    required this.background,
    required this.ink,
    required this.muted,
    required this.faint,
    required this.line,
    required this.surface,
    required this.primary,
    required this.onPrimary,
    required this.primarySoft,
    required this.accent,
    required this.highlight,
    required this.orange,
    required this.orangeText,
    required this.orangeTintOpacity,
    required this.ok,
    required this.scheduled,
    required this.todayFill,
    required this.scrim,
    required this.onBadge,
    required this.ownMarker,
    required this.freeEntry,
    required this.chatAvatars,
    required this.onChatAvatar,
    required this.switchKnobOn,
    required this.classFallback,
  });

  /// Page, sheet and navigation bar background.
  final Color background;

  /// Text, icons, secondary button frame and label.
  final Color ink;

  /// Secondary text, inactive tabs and navigation items.
  final Color muted;

  /// Past days, grab handle, used entries, disabled controls.
  final Color faint;

  /// Every divider and outline.
  final Color line;

  /// Quiet filled areas (waiting-list box, avatar placeholder).
  final Color surface;

  /// Primary button, next-class bar, switch on.
  final Color primary;

  /// Text and icons on [primary].
  final Color onPrimary;

  /// Selected row in a picker sheet, event chat avatar.
  final Color primarySoft;

  /// Selected bottom navigation icon and label.
  final Color accent;

  /// Hints such as "right after your class".
  final Color highlight;

  /// Today, changes, badges, ending pass.
  final Color orange;

  /// Text inside orange-tinted strips.
  final Color orangeText;

  /// Opacity of [orange] behind make-up and offer strips.
  final double orangeTintOpacity;

  /// "Active" status.
  final Color ok;

  /// "Scheduled" status.
  final Color scheduled;

  /// Today's cell in the month grid.
  final Color todayFill;

  /// Behind bottom sheets.
  final Color scrim;

  /// Number inside a badge.
  final Color onBadge;

  /// Marker of an own class under a calendar day.
  final Color ownMarker;

  /// Remaining entries on the pass bar.
  final Color freeEntry;

  /// Backgrounds of private chat avatars, picked by the person's name.
  final List<Color> chatAvatars;

  /// Initials on a private chat avatar.
  final Color onChatAvatar;

  /// Knob of a switch that is on.
  final Color switchKnobOn;

  /// Class colour when the service type has none.
  final Color classFallback;

  Color get orangeTint => orange.withValues(alpha: orangeTintOpacity);
}

/// One text style: size in logical pixels, weight, letter spacing in em and
/// line height as a multiple of the size.
class ClientTextSpec {
  const ClientTextSpec(
    this.size, {
    this.weight = FontWeight.w400,
    this.letterSpacingEm = 0,
    this.height = 1.3,
    this.upperCase = false,
  });

  final double size;
  final FontWeight weight;
  final double letterSpacingEm;
  final double height;

  /// Whether text in this style is written in capitals (section labels).
  final bool upperCase;
}

/// Every text style of the client app.
class ClientTypography {
  const ClientTypography({
    required this.fontFamily,
    required this.selectedWeight,
    required this.pageTitle,
    required this.sheetTitle,
    required this.sheetTitleSmall,
    required this.hero,
    required this.heroUnit,
    required this.weekDaySelected,
    required this.weekDay,
    required this.monthTitle,
    required this.nextClass,
    required this.when,
    required this.eventTitle,
    required this.eventTime,
    required this.rowTitle,
    required this.pickerTitle,
    required this.consequenceTitle,
    required this.changeLine,
    required this.body,
    required this.tab,
    required this.segment,
    required this.button,
    required this.buttonLarge,
    required this.buttonGhost,
    required this.meta,
    required this.caption,
    required this.lineValue,
    required this.lineLabel,
    required this.label,
    required this.status,
    required this.navLabel,
    required this.badge,
    required this.bigNumber,
    required this.profileName,
    required this.avatarInitials,
  });

  final String fontFamily;

  /// Weight of the selected tab, segment and navigation label.
  final FontWeight selectedWeight;

  /// Tab header ("Zajęcia", "Karnety").
  final ClientTextSpec pageTitle;

  /// Class sheet title.
  final ClientTextSpec sheetTitle;

  /// Picker, make-up and confirmation sheet titles.
  final ClientTextSpec sheetTitleSmall;

  /// Remaining entries on a pass.
  final ClientTextSpec hero;

  /// "z 8 wejść" next to [hero].
  final ClientTextSpec heroUnit;

  /// Selected day in the week strip.
  final ClientTextSpec weekDaySelected;

  /// Other days in the week strip and the month grid.
  final ClientTextSpec weekDay;

  /// Month name above the month grid.
  final ClientTextSpec monthTitle;

  /// Next-class bar title and the pass picker name.
  final ClientTextSpec nextClass;

  /// Date and time under a sheet title.
  final ClientTextSpec when;

  /// Class name in the day list.
  final ClientTextSpec eventTitle;

  /// Start time in the day list.
  final ClientTextSpec eventTime;

  /// List row title.
  final ClientTextSpec rowTitle;

  /// Row title in a picker or make-up sheet.
  final ClientTextSpec pickerTitle;

  /// Title of a cancellation consequence.
  final ClientTextSpec consequenceTitle;

  /// Orange change line under a class title in the day list.
  final ClientTextSpec changeLine;

  /// Default text.
  final ClientTextSpec body;

  /// Full-width tabs ("Moje karnety | Kup karnet").
  final ClientTextSpec tab;

  /// Segmented switch ("Moje | Wszystkie").
  final ClientTextSpec segment;

  /// Secondary and small buttons.
  final ClientTextSpec button;

  /// Primary button in a sheet.
  final ClientTextSpec buttonLarge;

  /// Frameless low-priority button and strip action.
  final ClientTextSpec buttonGhost;

  /// Row subtitles, warnings, strips.
  final ClientTextSpec meta;

  /// Key-value labels, sheet kicker, day headers.
  final ClientTextSpec caption;

  /// Value of a sheet line.
  final ClientTextSpec lineValue;

  /// Label above a sheet line value.
  final ClientTextSpec lineLabel;

  /// Section labels in capitals.
  final ClientTextSpec label;

  /// Pass status next to its dot.
  final ClientTextSpec status;

  /// Bottom navigation labels.
  final ClientTextSpec navLabel;

  /// Number inside a badge.
  final ClientTextSpec badge;

  /// Large numbers (waiting-list position, history summary).
  final ClientTextSpec bigNumber;

  /// Name on the profile tab.
  final ClientTextSpec profileName;

  /// Initials in the large profile avatar.
  final ClientTextSpec avatarInitials;
}

/// Lines, corners and outlines.
class ClientShape {
  const ClientShape({
    required this.radius,
    required this.hairline,
    required this.tabIndicator,
    required this.selectedDayUnderline,
    required this.monthSelectedUnderline,
    required this.focusOutline,
  });

  /// Corner radius of every box. The style has sharp corners: 0.
  final double radius;

  /// Width of dividers, outlines and button frames.
  final double hairline;

  /// Underline of the selected tab.
  final double tabIndicator;

  /// Orange underline under the selected day in the week strip.
  final double selectedDayUnderline;

  /// Ink underline under the selected day in the month grid.
  final double monthSelectedUnderline;

  /// Frame of a focused text field or a selected option.
  final double focusOutline;
}

/// Gaps and paddings.
class ClientSpacing {
  const ClientSpacing({
    required this.page,
    required this.sheet,
    required this.sheetBottom,
    required this.rowVertical,
    required this.section,
    required this.xs,
    required this.s,
    required this.m,
    required this.l,
    required this.xl,
  });

  /// Left and right padding of a screen.
  final double page;

  /// Left and right padding of a bottom sheet.
  final double sheet;

  /// Padding under the last item of a bottom sheet.
  final double sheetBottom;

  /// Top and bottom padding of a list row.
  final double rowVertical;

  /// Gap between a strip or picker and the content below it.
  final double section;

  final double xs;
  final double s;
  final double m;
  final double l;
  final double xl;
}

/// Sizes of components.
class ClientSizes {
  const ClientSizes({
    required this.buttonHeight,
    required this.buttonSmallHeight,
    required this.buttonPrimaryHeight,
    required this.buttonPairWidth,
    required this.buttonPadding,
    required this.buttonSmallPadding,
    required this.buttonGhostHeight,
    required this.iconButton,
    required this.tapTarget,
    required this.icon,
    required this.headerHeight,
    required this.navBarHeight,
    required this.navIcon,
    required this.badge,
    required this.segmentHeight,
    required this.grabWidth,
    required this.grabHeight,
    required this.statusDot,
    required this.unreadDot,
    required this.classSquare,
    required this.timeColumn,
    required this.keyColumn,
    required this.switchWidth,
    required this.switchHeight,
    required this.switchKnob,
    required this.entryBarHeight,
    required this.entryBarGap,
    required this.ownMarkerWidth,
    required this.ownMarkerHeight,
    required this.changeDot,
    required this.avatar,
    required this.avatarSmall,
    required this.consequenceIcon,
    required this.profileAvatar,
    required this.textFieldMinHeight,
    required this.weekStripHeight,
    required this.monthRowHeight,
  });

  /// Secondary button standing alone.
  final double buttonHeight;

  /// Secondary button in a pair or inside a card.
  final double buttonSmallHeight;

  /// Primary button in a sheet.
  final double buttonPrimaryHeight;

  /// Width of each button of a centred pair.
  final double buttonPairWidth;

  /// Horizontal padding inside a button.
  final double buttonPadding;

  /// Horizontal padding inside a small button.
  final double buttonSmallPadding;

  /// Frameless button.
  final double buttonGhostHeight;

  /// Tap box of an icon button (bell, history, filter).
  final double iconButton;

  /// Minimum touch target of any tappable element.
  final double tapTarget;

  /// Default icon size.
  final double icon;

  /// Height of a tab header with its title.
  final double headerHeight;

  /// Height of the bottom navigation, without the system inset.
  final double navBarHeight;

  final double navIcon;

  /// Minimum width and height of a count badge.
  final double badge;

  final double segmentHeight;
  final double grabWidth;
  final double grabHeight;

  /// Round dot before a pass status.
  final double statusDot;

  /// Square after an unread row title.
  final double unreadDot;

  /// Class colour square on the day list axis.
  final double classSquare;

  /// Width of the time column in the day list.
  final double timeColumn;

  /// Width of the label column in a key-value list.
  final double keyColumn;

  final double switchWidth;
  final double switchHeight;
  final double switchKnob;

  /// Height of the pass entry bar segments, and the gap between them.
  final double entryBarHeight;
  final double entryBarGap;

  /// Marker of an own class under a calendar day.
  final double ownMarkerWidth;
  final double ownMarkerHeight;

  /// Dot of a change under a calendar day.
  final double changeDot;

  /// Chat and person avatars.
  final double avatar;
  final double avatarSmall;

  /// Framed icon in the cancellation consequences.
  final double consequenceIcon;

  /// Large initials square on the profile tab.
  final double profileAvatar;

  final double textFieldMinHeight;

  /// Height of the collapsed week strip.
  final double weekStripHeight;

  /// Height of one row of the month grid.
  final double monthRowHeight;
}

/// Durations of animations.
class ClientMotion {
  const ClientMotion({required this.short, required this.medium});

  /// Small state changes (switches, tabs).
  final Duration short;

  /// Sheets, the week strip expanding into the month.
  final Duration medium;
}

/// Everything that makes up the client theme.
class ClientThemeConfig {
  const ClientThemeConfig({
    required this.light,
    required this.dark,
    required this.typography,
    required this.shape,
    required this.spacing,
    required this.sizes,
    required this.motion,
  });

  final ClientPalette light;
  final ClientPalette dark;
  final ClientTypography typography;
  final ClientShape shape;
  final ClientSpacing spacing;
  final ClientSizes sizes;
  final ClientMotion motion;
}
