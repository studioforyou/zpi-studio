import 'package:flutter/material.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/service_type_filter_labels.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_filter_sheet.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The filter button of the class offer and its bottom sheet.
class ClientScheduleFilterBar extends StatelessWidget {
  const ClientScheduleFilterBar({
    required this.filter,
    required this.events,
    required this.onChanged,
    this.selectedServiceLabels = const {},
    this.selectedInstructorLabels = const {},
    this.serviceTypes = const {},
    this.trainers = const [],
    super.key,
  });

  final ClientScheduleFilter filter;
  final List<GroupClass> events;
  final ValueChanged<ClientScheduleFilter> onChanged;
  final Map<String, String> selectedServiceLabels;
  final Map<String, String> selectedInstructorLabels;

  /// The studio's service catalogue by id: the class types, levels and age
  /// groups offered as choices.
  final Map<String, ScheduleServiceType> serviceTypes;

  /// Every active trainer, offered as choices.
  final List<StudioTrainer> trainers;

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
    // The catalogue comes first; loaded classes and selected names fill in
    // when it is missing or a class uses a service that is no longer offered.
    final offered = {
      for (final type in serviceTypes.values)
        if (type.isOfferedGroupClass) type.id: type,
    };
    final services = {
      for (final type in offered.values)
        if (type.name case final String name) type.id: name,
      for (final event in events)
        ClientScheduleFilter.serviceKey(event):
            event.serviceTypeName ?? event.name ?? l10n.classesTitle,
      ...selectedServiceLabels,
    };
    final instructors = {
      for (final trainer in trainers) trainer.staffId: trainer.displayName,
      for (final event in events)
        for (final person in event.staff)
          if (person.isLead && person.displayName != null)
            person.staffId: person.displayName!,
      ...selectedInstructorLabels,
    };
    final serviceTypeOptions = serviceTypeFilterOptions(
      offered,
      selectedLevels: filter.levels,
      selectedAgeGroups: filter.ageGroups,
    );
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _FilterSheet(
        initial: filter,
        services: _byLabel(services),
        // Trainers keep the server's order by last and first name.
        instructors: instructors,
        levels: {
          for (final level in serviceTypeOptions.levels)
            level: serviceLevelLabel(l10n, level),
        },
        ageGroups: serviceTypeOptions.ageGroups,
        onChanged: onChanged,
      ),
    );
  }

  static Map<String, String> _byLabel(Map<String, String> choices) =>
      Map.fromEntries(
        choices.entries.toList()..sort((a, b) => a.value.compareTo(b.value)),
      );
}

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({
    required this.initial,
    required this.services,
    required this.instructors,
    required this.levels,
    required this.ageGroups,
    required this.onChanged,
  });
  final ClientScheduleFilter initial;

  /// Choices by key, in the order they are shown.
  final Map<String, String> services;
  final Map<String, String> instructors;

  /// Level labels by code, from the easiest.
  final Map<String, String> levels;
  final List<AgeGroup> ageGroups;
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

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    return ScheduleFilterSheet(
      onClear: () => _update(const ClientScheduleFilter()),
      children: [
        ScheduleFilterChecklist(
          title: l10n.classesSelectService,
          options: widget.services,
          selected: _filter.services,
          onChanged: (services) =>
              _update(_filter.copyWith(services: services)),
        ),
        if (widget.levels.isNotEmpty) ...[
          const SizedBox(height: 8),
          ScheduleFilterChecklist(
            title: l10n.scheduleFilterLevel,
            options: widget.levels,
            selected: _filter.levels,
            onChanged: (levels) => _update(_filter.copyWith(levels: levels)),
          ),
        ],
        const SizedBox(height: 8),
        ScheduleFilterChecklist(
          title: l10n.classesSelectInstructor,
          options: widget.instructors,
          selected: _filter.instructors,
          onChanged: (instructors) =>
              _update(_filter.copyWith(instructors: instructors)),
        ),
        if (widget.ageGroups.isNotEmpty) ...[
          const SizedBox(height: 16),
          Text(l10n.scheduleFilterAgeGroup, style: textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (final group in widget.ageGroups)
                FilterChip(
                  label: Text(ageGroupLabel(l10n, group)),
                  selected: _filter.ageGroups.contains(group),
                  onSelected: (selected) => _update(
                    _filter.copyWith(
                      ageGroups: selected
                          ? {..._filter.ageGroups, group}
                          : ({..._filter.ageGroups}..remove(group)),
                    ),
                  ),
                ),
            ],
          ),
        ],
        const SizedBox(height: 8),
        SwitchListTile.adaptive(
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.classesAvailableOnly),
          value: _filter.availableOnly,
          onChanged: (value) => _update(_filter.copyWith(availableOnly: value)),
        ),
        SwitchListTile.adaptive(
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.scheduleFilterShowCancelled),
          value: _filter.showCancelled,
          onChanged: (value) => _update(_filter.copyWith(showCancelled: value)),
        ),
      ],
    );
  }
}
