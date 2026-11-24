import 'package:flutter/material.dart';

import 'client_theme_config.dart';
import 'client_theme_types.dart';

export 'client_theme_types.dart';

/// The client theme as widgets read it: `context.ct.colors.ink`,
/// `context.ct.text.pageTitle`, `context.ct.sizes.buttonHeight`.
///
/// All values come from [clientThemeConfig]; this class only turns them into
/// Flutter objects.
@immutable
class ClientTheme extends ThemeExtension<ClientTheme> {
  ClientTheme._(this.brightness, ClientThemeConfig config)
    : colors = brightness == Brightness.dark ? config.dark : config.light,
      typography = config.typography,
      shape = config.shape,
      spacing = config.spacing,
      sizes = config.sizes,
      motion = config.motion,
      text = ClientTextStyles._(
        config.typography,
        brightness == Brightness.dark ? config.dark : config.light,
      );

  factory ClientTheme.of(
    Brightness brightness, [
    ClientThemeConfig config = clientThemeConfig,
  ]) {
    if (!identical(config, clientThemeConfig)) {
      return ClientTheme._(brightness, config);
    }
    return brightness == Brightness.dark ? _dark : _light;
  }

  static final _light = ClientTheme._(Brightness.light, clientThemeConfig);
  static final _dark = ClientTheme._(Brightness.dark, clientThemeConfig);

  final Brightness brightness;
  final ClientPalette colors;
  final ClientTypography typography;
  final ClientTextStyles text;
  final ClientShape shape;
  final ClientSpacing spacing;
  final ClientSizes sizes;
  final ClientMotion motion;

  bool get isDark => brightness == Brightness.dark;

  /// Corner radius of every box.
  BorderRadius get radius => BorderRadius.circular(shape.radius);

  /// A 1 px line in the [ClientPalette.line] colour.
  BorderSide get hairline =>
      BorderSide(color: colors.line, width: shape.hairline);

  /// The shape of every box: [radius] corners, no outline.
  OutlinedBorder get boxShape => RoundedRectangleBorder(borderRadius: radius);

  @override
  ClientTheme copyWith() => this;

  @override
  ClientTheme lerp(ClientTheme? other, double t) =>
      other == null || t < 0.5 ? this : other;
}

/// The text styles of [ClientTypography], ready to use. Every style is in
/// [ClientPalette.ink]; recolour with `.copyWith(color: …)`.
@immutable
class ClientTextStyles {
  ClientTextStyles._(ClientTypography t, ClientPalette c)
    : pageTitle = _style(t, c, t.pageTitle),
      sheetTitle = _style(t, c, t.sheetTitle),
      sheetTitleSmall = _style(t, c, t.sheetTitleSmall),
      hero = _style(t, c, t.hero),
      heroUnit = _style(t, c, t.heroUnit, c.muted),
      weekDaySelected = _style(t, c, t.weekDaySelected),
      weekDay = _style(t, c, t.weekDay),
      monthTitle = _style(t, c, t.monthTitle),
      nextClass = _style(t, c, t.nextClass),
      when = _style(t, c, t.when),
      eventTitle = _style(t, c, t.eventTitle),
      eventTime = _style(t, c, t.eventTime),
      rowTitle = _style(t, c, t.rowTitle),
      pickerTitle = _style(t, c, t.pickerTitle),
      consequenceTitle = _style(t, c, t.consequenceTitle),
      changeLine = _style(t, c, t.changeLine, c.orange),
      body = _style(t, c, t.body),
      tab = _style(t, c, t.tab),
      segment = _style(t, c, t.segment),
      button = _style(t, c, t.button),
      buttonLarge = _style(t, c, t.buttonLarge),
      buttonGhost = _style(t, c, t.buttonGhost),
      meta = _style(t, c, t.meta, c.muted),
      caption = _style(t, c, t.caption, c.muted),
      lineValue = _style(t, c, t.lineValue),
      lineLabel = _style(t, c, t.lineLabel, c.muted),
      label = _style(t, c, t.label, c.muted),
      status = _style(t, c, t.status),
      navLabel = _style(t, c, t.navLabel, c.muted),
      badge = _style(t, c, t.badge, c.onBadge),
      bigNumber = _style(t, c, t.bigNumber),
      profileName = _style(t, c, t.profileName),
      avatarInitials = _style(t, c, t.avatarInitials),
      selectedWeight = t.selectedWeight,
      _labelUpperCase = t.label.upperCase;

  static TextStyle _style(
    ClientTypography t,
    ClientPalette c,
    ClientTextSpec spec, [
    Color? color,
  ]) => TextStyle(
    // Complete styles, like Material's own, so a switch between the client
    // and the trainer theme can animate between them.
    inherit: false,
    fontFamily: t.fontFamily,
    fontSize: spec.size,
    fontWeight: spec.weight,
    letterSpacing: spec.letterSpacingEm * spec.size,
    height: spec.height,
    color: color ?? c.ink,
    leadingDistribution: TextLeadingDistribution.even,
    textBaseline: TextBaseline.alphabetic,
    decoration: TextDecoration.none,
  );

  final TextStyle pageTitle;
  final TextStyle sheetTitle;
  final TextStyle sheetTitleSmall;
  final TextStyle hero;

  /// In [ClientPalette.muted].
  final TextStyle heroUnit;
  final TextStyle weekDaySelected;
  final TextStyle weekDay;
  final TextStyle monthTitle;
  final TextStyle nextClass;
  final TextStyle when;
  final TextStyle eventTitle;
  final TextStyle eventTime;
  final TextStyle rowTitle;
  final TextStyle pickerTitle;
  final TextStyle consequenceTitle;

  /// In [ClientPalette.orange].
  final TextStyle changeLine;
  final TextStyle body;
  final TextStyle tab;
  final TextStyle segment;
  final TextStyle button;
  final TextStyle buttonLarge;
  final TextStyle buttonGhost;

  /// In [ClientPalette.muted].
  final TextStyle meta;

  /// In [ClientPalette.muted].
  final TextStyle caption;
  final TextStyle lineValue;

  /// In [ClientPalette.muted].
  final TextStyle lineLabel;

  /// In [ClientPalette.muted]. Write the text with [upper] when the
  /// configuration asks for capitals.
  final TextStyle label;
  final TextStyle status;

  /// In [ClientPalette.muted].
  final TextStyle navLabel;

  /// In [ClientPalette.onBadge].
  final TextStyle badge;
  final TextStyle bigNumber;
  final TextStyle profileName;
  final TextStyle avatarInitials;

  /// Weight of the selected tab, segment and navigation label.
  final FontWeight selectedWeight;

  final bool _labelUpperCase;

  /// [text] in capitals when section labels are configured that way.
  String upper(String text) => _labelUpperCase ? text.toUpperCase() : text;
}

extension ClientThemeContext on BuildContext {
  /// The client theme. Outside a client theme (for example a widget test that
  /// pumps a bare `MaterialApp`) it falls back to the configured palette of
  /// the current brightness.
  ClientTheme get ct {
    final theme = Theme.of(this);
    return theme.extension<ClientTheme>() ?? ClientTheme.of(theme.brightness);
  }
}

/// Material [ThemeData] for the client app, built from [clientThemeConfig]:
/// sharp corners, no elevation, no ripples and the palette as the colour
/// scheme, so plain Material widgets on client screens already match.
ThemeData buildClientTheme(
  Brightness brightness, [
  ClientThemeConfig config = clientThemeConfig,
]) {
  final ct = ClientTheme.of(brightness, config);
  final c = ct.colors;
  final tx = ct.text;
  final s = ct.sizes;
  final box = ct.boxShape;
  final inkLine = BorderSide(color: c.ink, width: ct.shape.hairline);

  final scheme = ColorScheme(
    brightness: brightness,
    primary: c.primary,
    onPrimary: c.onPrimary,
    primaryContainer: c.primarySoft,
    onPrimaryContainer: c.ink,
    secondary: c.accent,
    onSecondary: c.background,
    secondaryContainer: c.primarySoft,
    onSecondaryContainer: c.ink,
    tertiary: c.orange,
    onTertiary: c.onBadge,
    error: c.orange,
    onError: c.onBadge,
    errorContainer: c.orangeTint,
    onErrorContainer: c.orangeText,
    surface: c.background,
    onSurface: c.ink,
    onSurfaceVariant: c.muted,
    surfaceContainerLowest: c.background,
    surfaceContainerLow: c.background,
    surfaceContainer: c.background,
    surfaceContainerHigh: c.surface,
    surfaceContainerHighest: c.surface,
    outline: c.line,
    outlineVariant: c.line,
    shadow: const Color(0x00000000),
    scrim: c.scrim,
    inverseSurface: c.ink,
    onInverseSurface: c.background,
    inversePrimary: c.accent,
    surfaceTint: const Color(0x00000000),
  );

  WidgetStateProperty<Color?> overlay() => WidgetStateProperty.resolveWith(
    (states) => states.contains(WidgetState.pressed)
        ? c.ink.withValues(alpha: 0.06)
        : null,
  );

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    fontFamily: config.typography.fontFamily,
    scaffoldBackgroundColor: c.background,
    canvasColor: c.background,
    dividerColor: c.line,
    disabledColor: c.faint,
    hintColor: c.muted,
    splashFactory: NoSplash.splashFactory,
    splashColor: const Color(0x00000000),
    highlightColor: c.ink.withValues(alpha: 0.04),
    hoverColor: c.ink.withValues(alpha: 0.03),
    focusColor: c.ink.withValues(alpha: 0.06),
    materialTapTargetSize: MaterialTapTargetSize.padded,
    visualDensity: VisualDensity.standard,
    iconTheme: IconThemeData(color: c.ink, size: s.icon),
    textTheme: TextTheme(
      displayLarge: tx.hero,
      displayMedium: tx.bigNumber,
      displaySmall: tx.sheetTitle,
      headlineLarge: tx.sheetTitle,
      headlineMedium: tx.pageTitle,
      headlineSmall: tx.sheetTitleSmall,
      titleLarge: tx.nextClass,
      titleMedium: tx.rowTitle,
      titleSmall: tx.consequenceTitle,
      bodyLarge: tx.body,
      bodyMedium: tx.body,
      bodySmall: tx.meta,
      labelLarge: tx.button,
      labelMedium: tx.caption,
      labelSmall: tx.navLabel,
    ),
    textSelectionTheme: TextSelectionThemeData(
      cursorColor: c.ink,
      selectionColor: c.primary.withValues(alpha: 0.5),
      selectionHandleColor: c.ink,
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: c.background,
      foregroundColor: c.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      surfaceTintColor: const Color(0x00000000),
      shadowColor: const Color(0x00000000),
      centerTitle: false,
      titleTextStyle: tx.eventTitle,
      iconTheme: IconThemeData(color: c.ink, size: s.icon),
      actionsIconTheme: IconThemeData(color: c.ink, size: s.icon),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: ButtonStyle(
        backgroundColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.disabled) ? c.line : c.primary,
        ),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.disabled) ? c.muted : c.onPrimary,
        ),
        overlayColor: overlay(),
        textStyle: WidgetStatePropertyAll(tx.buttonLarge),
        minimumSize: WidgetStatePropertyAll(Size(0, s.buttonPrimaryHeight)),
        padding: WidgetStatePropertyAll(
          EdgeInsets.symmetric(horizontal: s.buttonPadding),
        ),
        elevation: const WidgetStatePropertyAll(0),
        shape: WidgetStatePropertyAll(box),
        splashFactory: NoSplash.splashFactory,
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ButtonStyle(
        backgroundColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.disabled) ? c.line : c.primary,
        ),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.disabled) ? c.muted : c.onPrimary,
        ),
        overlayColor: overlay(),
        textStyle: WidgetStatePropertyAll(tx.buttonLarge),
        minimumSize: WidgetStatePropertyAll(Size(0, s.buttonPrimaryHeight)),
        elevation: const WidgetStatePropertyAll(0),
        shadowColor: const WidgetStatePropertyAll(Color(0x00000000)),
        shape: WidgetStatePropertyAll(box),
        splashFactory: NoSplash.splashFactory,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: ButtonStyle(
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.disabled) ? c.faint : c.ink,
        ),
        side: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.disabled)
              ? inkLine.copyWith(color: c.faint)
              : inkLine,
        ),
        overlayColor: overlay(),
        textStyle: WidgetStatePropertyAll(tx.button),
        minimumSize: WidgetStatePropertyAll(Size(0, s.buttonHeight)),
        padding: WidgetStatePropertyAll(
          EdgeInsets.symmetric(horizontal: s.buttonPadding),
        ),
        shape: WidgetStatePropertyAll(box),
        splashFactory: NoSplash.splashFactory,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: ButtonStyle(
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.disabled) ? c.faint : c.ink,
        ),
        overlayColor: overlay(),
        textStyle: WidgetStatePropertyAll(tx.buttonGhost),
        minimumSize: WidgetStatePropertyAll(Size(0, s.buttonGhostHeight)),
        shape: WidgetStatePropertyAll(box),
        splashFactory: NoSplash.splashFactory,
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: ButtonStyle(
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.disabled) ? c.faint : c.ink,
        ),
        overlayColor: overlay(),
        iconSize: WidgetStatePropertyAll(s.icon),
        minimumSize: WidgetStatePropertyAll(Size.square(s.tapTarget)),
        shape: WidgetStatePropertyAll(box),
        splashFactory: NoSplash.splashFactory,
      ),
    ),
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      backgroundColor: c.primary,
      foregroundColor: c.onPrimary,
      elevation: 0,
      focusElevation: 0,
      hoverElevation: 0,
      highlightElevation: 0,
      shape: box,
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        shape: WidgetStatePropertyAll(box),
        side: WidgetStatePropertyAll(ct.hairline),
        backgroundColor: const WidgetStatePropertyAll(Color(0x00000000)),
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) => states.contains(WidgetState.selected) ? c.ink : c.muted,
        ),
        textStyle: WidgetStatePropertyAll(tx.segment),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      border: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: ct.hairline,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: ct.hairline,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: inkLine.copyWith(width: ct.shape.focusOutline),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: ct.hairline.copyWith(color: c.orange),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: inkLine.copyWith(
          color: c.orange,
          width: ct.shape.focusOutline,
        ),
      ),
      disabledBorder: OutlineInputBorder(
        borderRadius: ct.radius,
        borderSide: ct.hairline,
      ),
      labelStyle: tx.body.copyWith(color: c.muted),
      floatingLabelStyle: tx.caption.copyWith(color: c.ink),
      hintStyle: tx.body.copyWith(color: c.muted),
      helperStyle: tx.meta,
      errorStyle: tx.meta.copyWith(color: c.orangeText),
      prefixIconColor: c.muted,
      suffixIconColor: c.muted,
      contentPadding: EdgeInsets.symmetric(
        horizontal: ct.spacing.m + 2,
        vertical: ct.spacing.m,
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: c.background,
      modalBackgroundColor: c.background,
      surfaceTintColor: const Color(0x00000000),
      elevation: 0,
      modalElevation: 0,
      shadowColor: const Color(0x00000000),
      modalBarrierColor: c.scrim,
      shape: Border(top: ct.hairline),
      showDragHandle: false,
      dragHandleColor: c.faint,
      dragHandleSize: Size(s.grabWidth, s.grabHeight),
      clipBehavior: Clip.hardEdge,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: c.background,
      surfaceTintColor: const Color(0x00000000),
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
      titleTextStyle: tx.sheetTitleSmall,
      contentTextStyle: tx.body,
      barrierColor: c.scrim,
    ),
    cardTheme: CardThemeData(
      color: c.background,
      surfaceTintColor: const Color(0x00000000),
      shadowColor: const Color(0x00000000),
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
    ),
    dividerTheme: DividerThemeData(
      color: c.line,
      thickness: ct.shape.hairline,
      space: ct.shape.hairline,
    ),
    listTileTheme: ListTileThemeData(
      titleTextStyle: tx.rowTitle,
      subtitleTextStyle: tx.meta,
      leadingAndTrailingTextStyle: tx.body.copyWith(color: c.muted),
      iconColor: c.ink,
      textColor: c.ink,
      contentPadding: EdgeInsets.symmetric(horizontal: ct.spacing.page),
      shape: box,
      selectedColor: c.ink,
      selectedTileColor: c.primarySoft,
    ),
    tabBarTheme: TabBarThemeData(
      labelColor: c.ink,
      unselectedLabelColor: c.muted,
      labelStyle: tx.tab.copyWith(fontWeight: tx.selectedWeight),
      unselectedLabelStyle: tx.tab,
      indicator: UnderlineTabIndicator(
        borderSide: BorderSide(color: c.ink, width: ct.shape.tabIndicator),
      ),
      indicatorSize: TabBarIndicatorSize.tab,
      dividerColor: c.line,
      dividerHeight: ct.shape.hairline,
      overlayColor: const WidgetStatePropertyAll(Color(0x00000000)),
      splashFactory: NoSplash.splashFactory,
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: c.background,
      surfaceTintColor: const Color(0x00000000),
      elevation: 0,
      indicatorColor: const Color(0x00000000),
      height: s.navBarHeight,
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          size: s.navIcon,
          color: states.contains(WidgetState.selected) ? c.accent : c.muted,
        ),
      ),
      labelTextStyle: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? tx.navLabel.copyWith(
                color: c.accent,
                fontWeight: tx.selectedWeight,
              )
            : tx.navLabel,
      ),
    ),
    badgeTheme: BadgeThemeData(
      backgroundColor: c.orange,
      textColor: c.onBadge,
      textStyle: tx.badge,
      smallSize: s.unreadDot,
      largeSize: s.badge,
      padding: EdgeInsets.symmetric(horizontal: ct.spacing.xs - 1),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: c.ink,
      contentTextStyle: tx.body.copyWith(color: c.background),
      actionTextColor: c.primary,
      elevation: 0,
      shape: box,
      behavior: SnackBarBehavior.fixed,
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
        (states) =>
            states.contains(WidgetState.selected) ? c.switchKnobOn : c.faint,
      ),
      trackColor: WidgetStateProperty.resolveWith(
        (states) =>
            states.contains(WidgetState.selected) ? c.primary : c.background,
      ),
      trackOutlineColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected) ? c.primary : c.line,
      ),
      overlayColor: const WidgetStatePropertyAll(Color(0x00000000)),
      splashRadius: 0,
    ),
    checkboxTheme: CheckboxThemeData(
      shape: box,
      side: inkLine,
      fillColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? c.ink
            : const Color(0x00000000),
      ),
      checkColor: WidgetStatePropertyAll(c.background),
      overlayColor: const WidgetStatePropertyAll(Color(0x00000000)),
      splashRadius: 0,
    ),
    radioTheme: RadioThemeData(
      fillColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected) ? c.ink : c.muted,
      ),
      overlayColor: const WidgetStatePropertyAll(Color(0x00000000)),
      splashRadius: 0,
    ),
    chipTheme: ChipThemeData(
      backgroundColor: c.background,
      selectedColor: c.primarySoft,
      disabledColor: c.surface,
      side: ct.hairline,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
      labelStyle: tx.segment,
      secondaryLabelStyle: tx.segment,
      checkmarkColor: c.ink,
      elevation: 0,
      pressElevation: 0,
      showCheckmark: false,
      surfaceTintColor: const Color(0x00000000),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: c.ink,
      linearTrackColor: c.line,
      circularTrackColor: const Color(0x00000000),
      linearMinHeight: ct.shape.tabIndicator,
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: c.background,
      surfaceTintColor: const Color(0x00000000),
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
      textStyle: tx.body,
      labelTextStyle: WidgetStatePropertyAll(tx.body),
    ),
    menuTheme: MenuThemeData(
      style: MenuStyle(
        backgroundColor: WidgetStatePropertyAll(c.background),
        surfaceTintColor: const WidgetStatePropertyAll(Color(0x00000000)),
        elevation: const WidgetStatePropertyAll(0),
        shape: WidgetStatePropertyAll(
          RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
        ),
      ),
    ),
    dropdownMenuTheme: DropdownMenuThemeData(
      textStyle: tx.body,
      menuStyle: MenuStyle(
        backgroundColor: WidgetStatePropertyAll(c.background),
        surfaceTintColor: const WidgetStatePropertyAll(Color(0x00000000)),
        elevation: const WidgetStatePropertyAll(0),
        shape: WidgetStatePropertyAll(
          RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
        ),
      ),
    ),
    datePickerTheme: DatePickerThemeData(
      backgroundColor: c.background,
      surfaceTintColor: const Color(0x00000000),
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
      headerBackgroundColor: c.background,
      headerForegroundColor: c.ink,
      dayShape: WidgetStatePropertyAll(box),
      todayBorder: BorderSide(color: c.orange, width: ct.shape.hairline),
      dayBackgroundColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected) ? c.primary : null,
      ),
      dayForegroundColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.selected)
            ? c.onPrimary
            : states.contains(WidgetState.disabled)
            ? c.faint
            : c.ink,
      ),
    ),
    timePickerTheme: TimePickerThemeData(
      backgroundColor: c.background,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: ct.radius, side: ct.hairline),
      hourMinuteShape: box,
      dayPeriodShape: box,
    ),
    tooltipTheme: TooltipThemeData(
      decoration: BoxDecoration(color: c.ink, borderRadius: ct.radius),
      textStyle: tx.caption.copyWith(color: c.background),
    ),
    expansionTileTheme: ExpansionTileThemeData(
      shape: const Border(),
      collapsedShape: const Border(),
      iconColor: c.muted,
      collapsedIconColor: c.muted,
      textColor: c.ink,
      collapsedTextColor: c.ink,
    ),
    extensions: [ct],
  );
}
