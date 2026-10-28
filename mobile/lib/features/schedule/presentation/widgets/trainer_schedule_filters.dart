import 'package:flutter/material.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/trainer_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Kinds a trainer can filter by; blocks are not part of a trainer's
/// schedule.
const _kinds = [
  EventKind.groupClass,
  EventKind.personalTraining,
  EventKind.workshop,
  EventKind.consultation,
];

/// The filter button above the trainer's calendar, with a reset when a
/// filter is on.
class TrainerScheduleFilterBar extends StatelessWidget {
  const TrainerScheduleFilterBar({
    super.key,
    required this.filter,
    required this.rooms,
    required this.onChanged,
  });

  final TrainerScheduleFilter filter;

  /// Rooms of the loaded events, offered as filters.
  final List<String> rooms;
  final ValueChanged<TrainerScheduleFilter> onChanged;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final count = filter.activeCount;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        spacing: 8,
        children: [
          ActionChip(
            avatar: const Icon(Icons.filter_list),
            label: Text(
              count == 0
                  ? l10n.scheduleFilters
                  : l10n.scheduleFiltersActive(count),
            ),
            onPressed: () => _openSheet(context),
          ),
          if (count > 0)
            TextButton(
              onPressed: () => onChanged(const TrainerScheduleFilter()),
              child: Text(l10n.scheduleFiltersClear),
            ),
        ],
      ),
    );
  }

  Future<void> _openSheet(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _FilterSheet(
        initial: filter,
        // A selected room stays on the list after moving to a period
        // without it, so it can still be turned off.
        rooms: {...rooms, ...filter.rooms}.toList()..sort(),
        onChanged: onChanged,
      ),
    );
  }
}

class _FilterSheet extends StatefulWidget {
  const _FilterSheet({
    required this.initial,
    required this.rooms,
    required this.onChanged,
  });

  final TrainerScheduleFilter initial;
  final List<String> rooms;
  final ValueChanged<TrainerScheduleFilter> onChanged;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late TrainerScheduleFilter _filter = widget.initial;

  /// Applies every change at once, so the calendar behind the sheet follows.
  void _update(TrainerScheduleFilter filter) {
    setState(() => _filter = filter);
    widget.onChanged(filter);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    return SafeArea(
      child: ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        children: [
          Text(l10n.scheduleFilterKind, style: textTheme.titleSmall),
          Wrap(
            spacing: 8,
            children: [
              for (final kind in _kinds)
                FilterChip(
                  avatar: CircleAvatar(
                    backgroundColor: eventKindColor(kind),
                    radius: 6,
                  ),
                  label: Text(eventKindLabel(l10n, kind)),
                  selected: _filter.kinds.contains(kind),
                  onSelected: (selected) => _update(
                    _filter.copyWith(
                      kinds: selected
                          ? {..._filter.kinds, kind}
                          : ({..._filter.kinds}..remove(kind)),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Text(l10n.scheduleFilterRole, style: textTheme.titleSmall),
          Wrap(
            spacing: 8,
            children: [
              for (final (role, label) in [
                (TrainerRoleFilter.any, l10n.scheduleFilterRoleAny),
                (TrainerRoleFilter.lead, l10n.scheduleFilterRoleLead),
                (TrainerRoleFilter.assistant, l10n.scheduleFilterRoleAssistant),
              ])
                ChoiceChip(
                  label: Text(label),
                  selected: _filter.role == role,
                  onSelected: (_) => _update(_filter.copyWith(role: role)),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Text(l10n.scheduleFilterRoom, style: textTheme.titleSmall),
          if (widget.rooms.isEmpty)
            Text(l10n.scheduleFilterNoRooms, style: textTheme.bodySmall)
          else
            Wrap(
              spacing: 8,
              children: [
                for (final room in widget.rooms)
                  FilterChip(
                    label: Text(room),
                    selected: _filter.rooms.contains(room),
                    onSelected: (selected) => _update(
                      _filter.copyWith(
                        rooms: selected
                            ? {..._filter.rooms, room}
                            : ({..._filter.rooms}..remove(room)),
                      ),
                    ),
                  ),
              ],
            ),
          const SizedBox(height: 4),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(l10n.scheduleFilterShowCancelled),
            value: _filter.showCancelled,
            onChanged: (value) =>
                _update(_filter.copyWith(showCancelled: value)),
          ),
        ],
      ),
    );
  }
}
