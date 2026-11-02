import 'package:flutter/material.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_filter.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Mirrors the trainer calendar's filter button and live bottom-sheet controls.
class ClientScheduleFilterBar extends StatelessWidget {
  const ClientScheduleFilterBar({
    required this.filter,
    required this.events,
    required this.onChanged,
    this.reservationsOnly = false,
    this.selectedServiceLabels = const {},
    this.selectedInstructorLabels = const {},
    super.key,
  });

  final ClientScheduleFilter filter;
  final List<GroupClass> events;
  final ValueChanged<ClientScheduleFilter> onChanged;
  final bool reservationsOnly;
  final Map<String, String> selectedServiceLabels;
  final Map<String, String> selectedInstructorLabels;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Wrap(
          spacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            ActionChip(
              avatar: const Icon(Icons.filter_list),
              label: Text(
                filter.activeCount == 0
                    ? l10n.scheduleFilters
                    : l10n.scheduleFiltersActive(filter.activeCount),
              ),
              onPressed: () => _openSheet(context),
            ),
            if (filter.activeCount > 0)
              TextButton(
                onPressed: () => onChanged(const ClientScheduleFilter()),
                child: Text(l10n.scheduleFiltersClear),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _openSheet(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final services = {
      ...selectedServiceLabels,
      for (final event in events)
        ClientScheduleFilter.serviceKey(event):
            event.serviceTypeName ?? event.name ?? l10n.classesTitle,
    };
    final instructors = {
      ...selectedInstructorLabels,
      for (final event in events)
        for (final person in event.staff)
          if (person.isLead && person.displayName != null)
            person.staffId: person.displayName!,
    };
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _FilterSheet(
        initial: filter,
        services: services,
        instructors: instructors,
        reservationsOnly: reservationsOnly,
        onChanged: onChanged,
      ),
    );
  }
}

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({
    required this.initial,
    required this.services,
    required this.instructors,
    required this.reservationsOnly,
    required this.onChanged,
  });
  final ClientScheduleFilter initial;
  final Map<String, String> services;
  final Map<String, String> instructors;
  final bool reservationsOnly;
  final ValueChanged<ClientScheduleFilter> onChanged;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late ClientScheduleFilter _filter = widget.initial;

  void _update(ClientScheduleFilter filter) {
    setState(() => _filter = filter);
    widget.onChanged(filter);
  }

  Widget _choices(
    String title,
    Map<String, String> choices,
    Set<String> selected,
    ValueChanged<Set<String>> onChanged,
  ) {
    final sorted = choices.entries.toList()
      ..sort((a, b) => a.value.compareTo(b.value));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final choice in sorted)
              FilterChip(
                label: Text(choice.value),
                selected: selected.contains(choice.key),
                onSelected: (value) => onChanged({
                  ...selected.where((id) => id != choice.key),
                  if (value) choice.key,
                }),
              ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * .8,
        ),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 16,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.scheduleFilters,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  TextButton(
                    onPressed: () => _update(const ClientScheduleFilter()),
                    child: Text(l10n.scheduleFiltersClear),
                  ),
                ],
              ),
              _choices(
                l10n.classesSelectService,
                widget.services,
                _filter.services,
                (services) => _update(_filter.copyWith(services: services)),
              ),
              _choices(
                l10n.classesSelectInstructor,
                widget.instructors,
                _filter.instructors,
                (instructors) =>
                    _update(_filter.copyWith(instructors: instructors)),
              ),
              if (!widget.reservationsOnly)
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: Text(l10n.classesAvailableOnly),
                  value: _filter.availableOnly,
                  onChanged: (value) =>
                      _update(_filter.copyWith(availableOnly: value)),
                ),
              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                title: Text(l10n.scheduleFilterShowCancelled),
                value: _filter.showCancelled,
                onChanged: (value) =>
                    _update(_filter.copyWith(showCancelled: value)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
