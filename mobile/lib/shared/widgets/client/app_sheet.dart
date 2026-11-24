import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Opens a client bottom sheet: square, over the scrim, as tall as its
/// content and scrollable when it does not fit.
Future<T?> showAppSheet<T>(
  BuildContext context, {
  required WidgetBuilder builder,
  bool useRootNavigator = true,
}) {
  final ct = context.ct;
  return showModalBottomSheet<T>(
    context: context,
    useRootNavigator: useRootNavigator,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: ct.colors.background,
    barrierColor: ct.colors.scrim,
    elevation: 0,
    shape: Border(top: ct.hairline),
    clipBehavior: Clip.hardEdge,
    builder: builder,
  );
}

/// A route page that shows [child] as a client bottom sheet, for sheets with
/// their own address (a class opened from a notification or a link).
class AppSheetPage<T> extends Page<T> {
  const AppSheetPage({super.key, super.name, required this.child});

  final Widget child;

  @override
  Route<T> createRoute(BuildContext context) {
    final ct = context.ct;
    return ModalBottomSheetRoute<T>(
      settings: this,
      builder: (_) => child,
      capturedThemes: InheritedTheme.capture(
        from: context,
        to: Navigator.of(context).context,
      ),
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: ct.colors.background,
      modalBarrierColor: ct.colors.scrim,
      elevation: 0,
      shape: Border(top: ct.hairline),
      clipBehavior: Clip.hardEdge,
      barrierLabel: MaterialLocalizations.of(context).scrimLabel,
    );
  }
}

/// The frame of a client bottom sheet: grab handle, a kicker row with the
/// close button, a title, the "when" line and then [children].
class AppSheet extends StatelessWidget {
  const AppSheet({
    super.key,
    this.kicker,
    this.title,
    this.titleSmall = false,
    this.subtitle,
    this.subtitleWidget,
    required this.children,
    this.footer,
    this.showClose = true,
  });

  /// Small muted line above the title ("Zajęcia grupowe", "Moje karnety").
  final String? kicker;
  final String? title;

  /// Uses the smaller sheet title (pickers, make-up, confirmations).
  final bool titleSmall;

  /// The "when" line under the title.
  final String? subtitle;

  /// Replaces [subtitle] when it needs mixed styles.
  final Widget? subtitleWidget;
  final List<Widget> children;

  /// Stays under the scrolling content (the primary action).
  final Widget? footer;
  final bool showClose;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final l10n = AppLocalizations.of(context);
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: EdgeInsets.only(top: sp.s + sp.xxs, bottom: sp.xs),
              child: Center(
                child: Container(
                  width: ct.sizes.grabWidth,
                  height: ct.sizes.grabHeight,
                  color: c.faint,
                ),
              ),
            ),
            if (kicker != null || showClose)
              Padding(
                padding: EdgeInsets.only(left: sp.sheet, right: sp.xs),
                child: Row(
                  children: [
                    Expanded(child: Text(kicker ?? '', style: ct.text.caption)),
                    if (showClose)
                      IconButton(
                        onPressed: () => Navigator.of(context).maybePop(),
                        tooltip: l10n.clientSheetClose,
                        icon: Icon(Icons.close, color: c.muted),
                      ),
                  ],
                ),
              ),
            Flexible(
              child: SingleChildScrollView(
                padding: EdgeInsets.fromLTRB(
                  sp.sheet,
                  0,
                  sp.sheet,
                  footer == null ? sp.sheetBottom : sp.l,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (title != null)
                      Semantics(
                        header: true,
                        child: Text(
                          title!,
                          style: titleSmall
                              ? ct.text.sheetTitleSmall
                              : ct.text.sheetTitle,
                        ),
                      ),
                    if (subtitleWidget != null || subtitle != null)
                      Padding(
                        padding: EdgeInsets.only(top: sp.s),
                        child:
                            subtitleWidget ??
                            Text(subtitle!, style: ct.text.when),
                      ),
                    ...children,
                  ],
                ),
              ),
            ),
            if (footer != null)
              Padding(
                padding: EdgeInsets.fromLTRB(
                  sp.sheet,
                  0,
                  sp.sheet,
                  sp.sheetBottom,
                ),
                child: footer,
              ),
          ],
        ),
      ),
    );
  }
}

/// A key-value list with hairlines between rows ("Prowadzi", "Sala").
class SheetKeyValues extends StatelessWidget {
  const SheetKeyValues({super.key, required this.entries});

  final List<(String, Widget)> entries;

  /// An entry with a plain text value.
  static (String, Widget) text(String key, String value) =>
      (key, _PlainValue(value));

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return Padding(
      padding: EdgeInsets.only(top: sp.l),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (key, value) in entries)
            Container(
              padding: EdgeInsets.symmetric(vertical: sp.m),
              decoration: BoxDecoration(border: Border(top: ct.hairline)),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  SizedBox(
                    width: ct.sizes.keyColumn,
                    child: Text(key, style: ct.text.caption),
                  ),
                  Expanded(child: value),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _PlainValue extends StatelessWidget {
  const _PlainValue(this.value);

  final String value;

  @override
  Widget build(BuildContext context) =>
      Text(value, style: context.ct.text.body);
}

/// A label above a value, with an optional link on the right ("Zmień").
class SheetLine extends StatelessWidget {
  const SheetLine({
    super.key,
    required this.label,
    required this.value,
    this.actionLabel,
    this.onAction,
  });

  final String label;
  final String value;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return Container(
      padding: EdgeInsets.symmetric(vertical: sp.m),
      decoration: BoxDecoration(border: Border(top: ct.hairline)),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: ct.text.lineLabel),
                Text(value, style: ct.text.lineValue),
              ],
            ),
          ),
          if (actionLabel != null)
            TextButton(
              onPressed: onAction,
              child: Text(
                actionLabel!,
                style: ct.text.buttonGhost.copyWith(
                  decoration: TextDecoration.underline,
                  decorationColor: ct.colors.ink,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Plain muted text under an action (cancellation rules, limits).
class SheetWarning extends StatelessWidget {
  const SheetWarning(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.only(top: ct.spacing.s + ct.spacing.xxs),
      child: Text(text, style: ct.text.warning),
    );
  }
}

/// A short hint in the highlight colour ("Zaraz po Twoich zajęciach").
class SheetHint extends StatelessWidget {
  const SheetHint(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.only(top: ct.spacing.section),
      child: Text(
        text,
        style: ct.text.strip.copyWith(color: ct.colors.highlight),
      ),
    );
  }
}

/// An orange-tinted box that names a change ("Nowa godzina · zwykle 18:00").
class ChangeStrip extends StatelessWidget {
  const ChangeStrip(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return Padding(
      padding: EdgeInsets.only(top: sp.section),
      child: Container(
        padding: EdgeInsets.symmetric(
          horizontal: sp.m,
          vertical: sp.s + sp.xxs,
        ),
        color: ct.colors.orangeTint,
        child: Text(text, style: ct.text.strip),
      ),
    );
  }
}

/// One item of a [ConsequenceList].
class Consequence {
  const Consequence({
    required this.icon,
    required this.title,
    this.subtitle,
    this.positive = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;

  /// Draws the icon on the primary fill (what the person keeps).
  final bool positive;
}

/// What happens after an action, as framed icons with a title and subtitle.
class ConsequenceList extends StatelessWidget {
  const ConsequenceList({super.key, required this.items});

  final List<Consequence> items;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final size = ct.sizes.consequenceIcon;
    return Container(
      margin: EdgeInsets.only(top: sp.l),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final item in items)
            Container(
              padding: EdgeInsets.symmetric(vertical: sp.m),
              decoration: BoxDecoration(border: Border(top: ct.hairline)),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: size,
                    height: size,
                    decoration: BoxDecoration(
                      color: item.positive ? c.primary : null,
                      border: Border.all(
                        color: item.positive ? c.primary : c.line,
                        width: ct.shape.hairline,
                      ),
                    ),
                    child: Icon(
                      item.icon,
                      size: ct.sizes.consequenceGlyph,
                      color: item.positive ? c.onPrimary : c.ink,
                    ),
                  ),
                  SizedBox(width: sp.m),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(item.title, style: ct.text.consequenceTitle),
                        if (item.subtitle != null)
                          Text(item.subtitle!, style: ct.text.meta),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// One option of [ChoiceOptions].
class ChoiceOption<T> {
  const ChoiceOption({
    required this.value,
    required this.title,
    this.subtitle,
    this.enabled = true,
  });

  final T value;
  final String title;
  final String? subtitle;
  final bool enabled;
}

/// Stacked framed options with one selected (pass choice when booking). The
/// selected option has an ink frame.
class ChoiceOptions<T> extends StatelessWidget {
  const ChoiceOptions({
    super.key,
    required this.options,
    required this.selected,
    required this.onChanged,
  });

  final List<ChoiceOption<T>> options;
  final T? selected;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (i, option) in options.indexed)
          Padding(
            padding: EdgeInsets.only(top: i == 0 ? 0 : sp.s - sp.xxs),
            child: Semantics(
              inMutuallyExclusiveGroup: true,
              checked: option.value == selected,
              enabled: option.enabled,
              child: InkWell(
                onTap: option.enabled ? () => onChanged(option.value) : null,
                child: Container(
                  padding: EdgeInsets.symmetric(
                    horizontal: sp.m + sp.xxs,
                    vertical: sp.m,
                  ),
                  decoration: BoxDecoration(
                    border: Border.all(
                      color: option.value == selected ? c.ink : c.line,
                      width: ct.shape.focusOutline,
                    ),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              option.title,
                              style: ct.text.rowTitle.copyWith(
                                color: option.enabled ? c.ink : c.faint,
                              ),
                            ),
                            if (option.subtitle != null)
                              Text(
                                option.subtitle!,
                                style: ct.text.meta.copyWith(
                                  color: option.enabled ? c.muted : c.faint,
                                ),
                              ),
                          ],
                        ),
                      ),
                      if (option.value == selected)
                        Icon(Icons.check, color: c.ink, size: ct.sizes.icon),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// The waiting-list box: a big position number and a line of text.
class WaitlistBox extends StatelessWidget {
  const WaitlistBox({
    super.key,
    required this.number,
    required this.title,
    this.subtitle,
  });

  final String number;
  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return Padding(
      padding: EdgeInsets.only(top: sp.l),
      child: Container(
        padding: EdgeInsets.all(sp.l),
        decoration: BoxDecoration(
          color: ct.colors.surface,
          border: Border.fromBorderSide(ct.hairline),
        ),
        child: Row(
          children: [
            Text(number, style: ct.text.bigNumber),
            SizedBox(width: sp.l),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: ct.text.consequenceTitle),
                  if (subtitle != null) Text(subtitle!, style: ct.text.meta),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A multi-line text field in an ink frame (dispute reason, message).
class AppTextArea extends StatelessWidget {
  const AppTextArea({
    super.key,
    required this.controller,
    this.hint,
    this.onChanged,
    this.maxLength,
    this.autofocus = false,
  });

  final TextEditingController controller;
  final String? hint;
  final ValueChanged<String>? onChanged;
  final int? maxLength;
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final ink = BorderSide(color: ct.colors.ink, width: ct.shape.hairline);
    final border = OutlineInputBorder(borderRadius: ct.radius, borderSide: ink);
    return ConstrainedBox(
      constraints: BoxConstraints(minHeight: ct.sizes.textFieldMinHeight),
      child: TextField(
        controller: controller,
        onChanged: onChanged,
        autofocus: autofocus,
        maxLength: maxLength,
        minLines: 4,
        maxLines: 8,
        style: ct.text.body,
        decoration: InputDecoration(
          hintText: hint,
          border: border,
          enabledBorder: border,
          focusedBorder: border,
        ),
      ),
    );
  }
}
