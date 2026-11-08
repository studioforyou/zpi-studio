import 'package:flutter/material.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

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
              onChanged: (checked) => onChanged({
                ...selected.where((id) => id != key),
                if (checked ?? false) key,
              }),
            ),
        ],
      ),
    );
  }
}
