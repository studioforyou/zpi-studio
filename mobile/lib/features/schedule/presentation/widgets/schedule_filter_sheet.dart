import 'package:flutter/material.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Share of the screen height a filter sheet takes, whatever the number of
/// filters, so long lists scroll inside it instead of leaving the screen.
const scheduleFilterSheetHeightFactor = .8;

/// The frame of a schedule filter bottom sheet: a fixed height, a header
/// with the title, a reset and a close button, and a scrollable body.
class ScheduleFilterSheet extends StatelessWidget {
  const ScheduleFilterSheet({
    required this.onClear,
    required this.children,
    super.key,
  });

  final VoidCallback onClear;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final media = MediaQuery.of(context);
    if (context.inClientTheme) return _client(context, l10n, media);
    return SizedBox(
      height:
          (media.size.height - media.padding.top) *
          scheduleFilterSheetHeightFactor,
      child: SafeArea(
        top: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.scheduleFilters,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  TextButton(
                    onPressed: onClear,
                    child: Text(l10n.scheduleFiltersClear),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: l10n.scheduleFiltersClose,
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
                children: children,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

extension on ScheduleFilterSheet {
  /// The client's look (#281): a small title, "Wyczyść" and the close
  /// button, then full-width rows divided by hairlines.
  Widget _client(
    BuildContext context,
    AppLocalizations l10n,
    MediaQueryData media,
  ) {
    final ct = context.ct;
    final sp = ct.spacing;
    return SizedBox(
      height:
          (media.size.height - media.padding.top) *
          scheduleFilterSheetHeightFactor,
      child: SafeArea(
        top: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: EdgeInsets.fromLTRB(sp.sheet, 0, sp.xs, sp.s),
              decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.scheduleFilters,
                      style: ct.text.sheetTitleSmall,
                    ),
                  ),
                  AppButton.ghost(
                    label: l10n.scheduleFiltersClear,
                    onPressed: onClear,
                  ),
                  IconButton(
                    icon: Icon(Icons.close, color: ct.colors.muted),
                    tooltip: l10n.scheduleFiltersClose,
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ListView(
                padding: EdgeInsets.only(bottom: sp.sheetBottom),
                children: children,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A row with a title and an [AppSwitch] in a client filter sheet; the whole
/// row toggles.
class ScheduleFilterSwitchRow extends StatelessWidget {
  const ScheduleFilterSwitchRow({
    required this.title,
    required this.value,
    required this.onChanged,
    super.key,
  });

  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    return InkWell(
      onTap: () => onChanged(!value),
      child: Container(
        constraints: BoxConstraints(minHeight: ct.sizes.tapTarget),
        padding: EdgeInsets.symmetric(horizontal: sp.page),
        decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
        child: Row(
          children: [
            Expanded(child: Text(title, style: ct.text.rowTitle)),
            SizedBox(width: sp.m),
            AppSwitch(value: value, semanticLabel: title, onChanged: onChanged),
          ],
        ),
      ),
    );
  }
}

/// A collapsible list of checkboxes for one criterion, so a long list of
/// options does not crowd the sheet. The title counts the selected options.
class ScheduleFilterChecklist extends StatelessWidget {
  const ScheduleFilterChecklist({
    required this.title,
    required this.options,
    required this.selected,
    required this.onChanged,
    super.key,
  });

  final String title;

  /// Option labels by key, in the order they are shown.
  final Map<String, String> options;
  final Set<String> selected;
  final ValueChanged<Set<String>> onChanged;

  @override
  Widget build(BuildContext context) {
    final count = selected.where(options.containsKey).length;
    void toggle(String key, bool checked) =>
        onChanged({...selected.where((id) => id != key), if (checked) key});
    if (context.inClientTheme) {
      final ct = context.ct;
      final border = Border(bottom: ct.hairline);
      return ExpansionTile(
        title: Text(
          count == 0 ? title : '$title ($count)',
          style: ct.text.rowTitle,
        ),
        tilePadding: EdgeInsets.symmetric(horizontal: ct.spacing.page),
        iconColor: ct.colors.ink,
        collapsedIconColor: ct.colors.muted,
        shape: border,
        collapsedShape: border,
        children: [
          for (final MapEntry(:key, :value) in options.entries)
            AppCheckRow(
              value: selected.contains(key),
              title: value,
              onChanged: (checked) => toggle(key, checked),
            ),
        ],
      );
    }
    return Card.outlined(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        title: Text(count == 0 ? title : '$title ($count)'),
        shape: const Border(),
        collapsedShape: const Border(),
        children: [
          for (final MapEntry(:key, :value) in options.entries)
            CheckboxListTile(
              dense: true,
              controlAffinity: ListTileControlAffinity.leading,
              title: Text(value),
              value: selected.contains(key),
              onChanged: (checked) => toggle(key, checked ?? false),
            ),
        ],
      ),
    );
  }
}
