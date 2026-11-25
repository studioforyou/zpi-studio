import 'package:flutter/material.dart';
import 'package:mobile/app/theme/client/client_theme.dart';
import 'package:mobile/shared/widgets/client/app_sheet.dart';
import 'package:mobile/shared/widgets/client/status.dart';

/// One row of a [PickerSheet].
class PickerOption<T> {
  const PickerOption({
    required this.value,
    required this.title,
    this.subtitle,
    this.initials,
    this.trailing,
  });

  final T value;
  final String title;
  final String? subtitle;

  /// Starts the row with an initials square (person picker).
  final String? initials;

  /// Shown before the tick, such as a pass status.
  final Widget? trailing;
}

/// Opens a [PickerSheet] and returns the picked value, or `null` when the
/// person closes it.
Future<T?> showPickerSheet<T>(
  BuildContext context, {
  required String kicker,
  required String title,
  required List<PickerOption<T>> options,
  required T? selected,
}) => showAppSheet<T>(
  context,
  builder: (context) => PickerSheet<T>(
    kicker: kicker,
    title: title,
    options: options,
    selected: selected,
    onPicked: (value) => Navigator.of(context).pop(value),
  ),
);

/// A sheet listing options with hairlines between them. The selected row has
/// the soft primary background and a tick.
class PickerSheet<T> extends StatelessWidget {
  const PickerSheet({
    super.key,
    required this.kicker,
    required this.title,
    required this.options,
    required this.selected,
    required this.onPicked,
  });

  final String kicker;
  final String title;
  final List<PickerOption<T>> options;
  final T? selected;
  final ValueChanged<T> onPicked;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return AppSheet(
      kicker: kicker,
      title: title,
      titleSmall: true,
      children: [
        SizedBox(height: sp.section),
        DecoratedBox(
          decoration: BoxDecoration(border: Border(top: ct.hairline)),
          child: Column(
            children: [
              for (final option in options)
                Semantics(
                  selected: option.value == selected,
                  button: true,
                  child: InkWell(
                    onTap: () => onPicked(option.value),
                    child: Container(
                      color: option.value == selected ? c.primarySoft : null,
                      padding: EdgeInsets.symmetric(
                        horizontal: sp.m,
                        vertical: sp.rowVertical,
                      ),
                      decoration: null,
                      foregroundDecoration: BoxDecoration(
                        border: Border(bottom: ct.hairline),
                      ),
                      child: Row(
                        children: [
                          if (option.initials != null) ...[
                            SquareAvatar(
                              initials: option.initials!,
                              background: c.primary,
                              foreground: c.onPrimary,
                              size: ct.sizes.avatarSmall,
                            ),
                            SizedBox(width: sp.m),
                          ],
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(option.title, style: ct.text.pickerTitle),
                                if (option.subtitle != null) ...[
                                  SizedBox(height: sp.xxs),
                                  Text(option.subtitle!, style: ct.text.meta),
                                ],
                              ],
                            ),
                          ),
                          if (option.trailing != null) ...[
                            SizedBox(width: sp.m),
                            option.trailing!,
                          ],
                          SizedBox(width: sp.m),
                          SizedBox(
                            width: ct.sizes.icon,
                            child: option.value == selected
                                ? Icon(
                                    Icons.check,
                                    color: c.ink,
                                    size: ct.sizes.icon,
                                  )
                                : null,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

/// A framed field that opens a picker: the chosen name with a subtitle under
/// it, an optional status on the right and a chevron.
class PickerField extends StatelessWidget {
  const PickerField({
    super.key,
    required this.title,
    this.subtitle,
    this.status,
    required this.onTap,
  });

  final String title;
  final String? subtitle;
  final Widget? status;

  /// `null` shows the field without the chevron (only one option).
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Semantics(
      button: onTap != null,
      child: InkWell(
        onTap: onTap,
        child: Container(
          padding: EdgeInsets.fromLTRB(sp.m + sp.xxs, sp.m - 1, sp.m, sp.m - 1),
          decoration: BoxDecoration(border: Border.fromBorderSide(ct.hairline)),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: ct.text.nextClass,
                    ),
                    if (subtitle != null) ...[
                      SizedBox(height: sp.xxs + 1),
                      Text(subtitle!, style: ct.text.caption),
                    ],
                  ],
                ),
              ),
              if (status != null) ...[
                SizedBox(width: sp.m),
                Flexible(flex: 0, child: status!),
              ],
              if (onTap != null) ...[
                SizedBox(width: sp.xs),
                Icon(Icons.expand_more, color: c.muted, size: ct.sizes.icon),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// A low framed field that opens the person picker: a person icon, the
/// name, a short note ("Ty", "Dziecko") and a chevron.
class PersonPickerField extends StatelessWidget {
  const PersonPickerField({
    super.key,
    required this.name,
    this.note,
    required this.onTap,
  });

  final String name;
  final String? note;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    return Semantics(
      button: onTap != null,
      child: InkWell(
        onTap: onTap,
        child: Container(
          constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
          padding: EdgeInsets.symmetric(horizontal: sp.m),
          decoration: BoxDecoration(border: Border.fromBorderSide(ct.hairline)),
          child: Row(
            children: [
              Icon(Icons.person_outline, color: c.muted, size: ct.sizes.icon),
              SizedBox(width: sp.s + sp.xxs),
              Expanded(
                child: Row(
                  children: [
                    Flexible(
                      child: Text(
                        name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: ct.text.consequenceTitle,
                      ),
                    ),
                    if (note != null) ...[
                      SizedBox(width: sp.s),
                      Text(note!, style: ct.text.caption),
                    ],
                  ],
                ),
              ),
              if (onTap != null)
                Icon(Icons.expand_more, color: c.muted, size: ct.sizes.icon),
            ],
          ),
        ),
      ),
    );
  }
}
