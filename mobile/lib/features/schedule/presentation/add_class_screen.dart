import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/plan_messages.dart';
import 'package:mobile/features/schedule/presentation/time_input.dart';
import 'package:mobile/features/schedule/presentation/widgets/plan_preview_section.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// How long the form waits after a change before it checks the plan.
const previewDelay = Duration(milliseconds: 400);

/// Whether the class happens once or repeats every week.
enum ClassRepeat { once, weekly }

/// Lets the trainer add a one-off or recurring group class run by
/// themselves, showing conflicts with other classes, rooms and leave before
/// saving.
class AddClassScreen extends ConsumerStatefulWidget {
  const AddClassScreen({super.key, this.initialDay});

  /// The day the form starts with; tomorrow when `null`.
  final DateTime? initialDay;

  @override
  ConsumerState<AddClassScreen> createState() => _AddClassScreenState();
}

class _AddClassScreenState extends ConsumerState<AddClassScreen> {
  final _capacityController = TextEditingController();
  final _durationController = TextEditingController(text: '60');
  final _startTimeController = TextEditingController(text: '18:00');

  ClassRepeat _repeat = ClassRepeat.once;
  PlanningServiceType? _serviceType;
  PlanningRoom? _room;
  late DateTime _day;
  Set<int> _weekdays = {};
  late DateTime _endsOn;
  Set<DateTime> _excludedDates = {};

  Timer? _previewTimer;

  /// Counts preview requests, so that only the latest one is shown.
  int _previewRequest = 0;
  bool _previewLoading = false;
  PlanPreview? _preview;
  Object? _previewError;

  bool _saving = false;
  Object? _saveError;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    final initial =
        widget.initialDay ?? DateTime(now.year, now.month, now.day + 1);
    _day = DateTime(initial.year, initial.month, initial.day);
    _weekdays = {_day.weekday};
    _endsOn = DateTime(_day.year, _day.month + 1, _day.day);
  }

  @override
  void dispose() {
    _previewTimer?.cancel();
    _capacityController.dispose();
    _durationController.dispose();
    _startTimeController.dispose();
    super.dispose();
  }

  int? get _duration {
    final minutes = int.tryParse(_durationController.text.trim());
    return minutes != null && minutes >= 5 && minutes <= 720 ? minutes : null;
  }

  /// `null` while the field does not hold a time from 00:00 to 23:59.
  TimeOfDay? get _startTime => parseTime(_startTimeController.text);

  /// `null` when the field is empty, so the service type's limit applies.
  int? get _capacity => int.tryParse(_capacityController.text.trim());

  bool get _capacityValid {
    final text = _capacityController.text.trim();
    return text.isEmpty || (_capacity != null && _capacity! >= 1);
  }

  /// The plan the form describes, or `null` while it is incomplete.
  ClassPlan? get _plan {
    final serviceType = _serviceType;
    final room = _room;
    final duration = _duration;
    final startTime = _startTime;
    if (serviceType == null ||
        room == null ||
        duration == null ||
        startTime == null ||
        !_capacityValid) {
      return null;
    }
    switch (_repeat) {
      case ClassRepeat.once:
        final startsAt = DateTime(
          _day.year,
          _day.month,
          _day.day,
          startTime.hour,
          startTime.minute,
        );
        return OneOffClassPlan(
          serviceTypeId: serviceType.id,
          roomId: room.id,
          capacity: _capacity,
          startsAt: startsAt,
          endsAt: startsAt.add(Duration(minutes: duration)),
        );
      case ClassRepeat.weekly:
        if (_weekdays.isEmpty) {
          return null;
        }
        return RecurringClassPlan(
          serviceTypeId: serviceType.id,
          roomId: room.id,
          capacity: _capacity,
          weekdays: _weekdays,
          startTime: startTime,
          durationMinutes: duration,
          startsOn: _day,
          endsOn: _endsOn,
          excludedDates: _excludedDates,
        );
    }
  }

  /// Applies a change of the form and checks the new plan after a pause.
  void _changed(VoidCallback change, {bool keepExcludedDates = false}) {
    setState(() {
      change();
      if (!keepExcludedDates) {
        _excludedDates = {};
      }
      _saveError = null;
      _preview = null;
      _previewError = null;
    });
    _schedulePreview();
  }

  void _schedulePreview() {
    _previewTimer?.cancel();
    final plan = _plan;
    if (plan == null) {
      setState(() => _previewLoading = false);
      return;
    }
    setState(() => _previewLoading = true);
    _previewTimer = Timer(previewDelay, () => _runPreview(plan));
  }

  Future<void> _runPreview(ClassPlan plan) async {
    final request = ++_previewRequest;
    try {
      final preview = await ref.read(classPlanningApiProvider).preview(plan);
      if (mounted && request == _previewRequest) {
        setState(() {
          _preview = preview;
          _previewError = null;
          _previewLoading = false;
        });
      }
    } catch (e) {
      if (mounted && request == _previewRequest) {
        setState(() {
          _preview = null;
          _previewError = e;
          _previewLoading = false;
        });
      }
    }
  }

  void _toggleExcluded(DateTime date) {
    _changed(() {
      final excluded = {..._excludedDates};
      if (!excluded.remove(date)) {
        excluded.add(date);
      }
      _excludedDates = excluded;
    }, keepExcludedDates: true);
  }

  bool get _canSave =>
      !_saving &&
      !_previewLoading &&
      _plan != null &&
      _preview != null &&
      _preview!.conflictCount == 0 &&
      _preview!.occurrences.any((occurrence) => !occurrence.excluded);

  Future<void> _save() async {
    final plan = _plan;
    if (!_canSave || plan == null) {
      return;
    }
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      await ref.read(classPlanningApiProvider).create(plan);
      ref.invalidate(ownScheduleProvider);
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppLocalizations.of(context).addClassSaved)),
      );
      context.pop();
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() => _saveError = e);
      // Someone may have taken the time meanwhile: show the current conflicts.
      if (e is ApiException && e.statusCode == 409) {
        _schedulePreview();
      }
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final serviceTypes = ref.watch(planningServiceTypesProvider);
    final rooms = ref.watch(planningRoomsProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.addClassTitle)),
      body: switch ((serviceTypes, rooms)) {
        (AsyncError(:final error), _) ||
        (_, AsyncError(:final error)) => _loadError(error, l10n),
        (AsyncData(value: final serviceTypes), AsyncData(value: final rooms)) =>
          _form(serviceTypes, rooms, l10n),
        _ => const Center(child: CircularProgressIndicator()),
      },
    );
  }

  Widget _loadError(Object error, AppLocalizations l10n) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(l10n.addClassLoadError, textAlign: TextAlign.center),
            OutlinedButton.icon(
              onPressed: () {
                ref.invalidate(planningServiceTypesProvider);
                ref.invalidate(planningRoomsProvider);
              },
              icon: const Icon(Icons.refresh),
              label: Text(l10n.scheduleRetryButton),
            ),
          ],
        ),
      ),
    );
  }

  Widget _form(
    List<PlanningServiceType> serviceTypes,
    List<PlanningRoom> rooms,
    AppLocalizations l10n,
  ) {
    final dateFormat = DateFormat('EEEE, d MMMM y', 'pl');
    final preview = _preview;

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 16,
          children: [
            SegmentedButton<ClassRepeat>(
              segments: [
                ButtonSegment(
                  value: ClassRepeat.once,
                  icon: const Icon(Icons.event),
                  label: Text(l10n.addClassOnce),
                ),
                ButtonSegment(
                  value: ClassRepeat.weekly,
                  icon: const Icon(Icons.repeat),
                  label: Text(l10n.addClassWeekly),
                ),
              ],
              selected: {_repeat},
              onSelectionChanged: _saving
                  ? null
                  : (selected) => _changed(() => _repeat = selected.single),
            ),
            if (serviceTypes.isEmpty)
              Text(l10n.addClassNoServiceTypes)
            else
              DropdownButtonFormField<PlanningServiceType>(
                key: const Key('addClassServiceType'),
                initialValue: _serviceType,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: l10n.addClassServiceType,
                  prefixIcon: const Icon(Icons.fitness_center),
                  border: const OutlineInputBorder(),
                ),
                items: [
                  for (final serviceType in serviceTypes)
                    DropdownMenuItem(
                      value: serviceType,
                      child: Text(
                        serviceType.name,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: _saving
                    ? null
                    : (serviceType) => _changed(() {
                        _serviceType = serviceType;
                        if (serviceType != null) {
                          _durationController.text = serviceType
                              .defaultDurationMinutes
                              .toString();
                        }
                      }),
              ),
            if (rooms.isEmpty)
              Text(l10n.addClassNoRooms)
            else
              DropdownButtonFormField<PlanningRoom>(
                key: const Key('addClassRoom'),
                initialValue: _room,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: l10n.addClassRoom,
                  prefixIcon: const Icon(Icons.meeting_room_outlined),
                  border: const OutlineInputBorder(),
                ),
                items: [
                  for (final room in rooms)
                    DropdownMenuItem(
                      value: room,
                      child: Text(
                        room.maxPeople == null
                            ? room.name
                            : l10n.addClassRoomWithLimit(
                                room.name,
                                room.maxPeople!,
                              ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: _saving
                    ? null
                    : (room) => _changed(() => _room = room),
              ),
            TextField(
              key: const Key('addClassCapacity'),
              controller: _capacityController,
              enabled: !_saving,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: l10n.addClassCapacity,
                helperText: _serviceType?.maxParticipants == null
                    ? l10n.addClassCapacityHint
                    : l10n.addClassCapacityDefault(
                        _serviceType!.maxParticipants!,
                      ),
                errorText: _capacityValid ? null : l10n.addClassCapacityInvalid,
                prefixIcon: const Icon(Icons.groups_outlined),
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => _changed(() {}),
            ),
            _PickerTile(
              key: const Key('addClassDay'),
              icon: Icons.calendar_today,
              label: _repeat == ClassRepeat.once
                  ? l10n.addClassDay
                  : l10n.addClassStartsOn,
              value: dateFormat.format(_day),
              onTap: _saving ? null : () => _pickDay(start: true),
            ),
            if (_repeat == ClassRepeat.weekly) ...[
              _PickerTile(
                key: const Key('addClassEndsOn'),
                icon: Icons.event_busy,
                label: l10n.addClassEndsOn,
                value: dateFormat.format(_endsOn),
                onTap: _saving ? null : () => _pickDay(start: false),
              ),
              _WeekdayPicker(
                selected: _weekdays,
                enabled: !_saving,
                onChanged: (weekdays) => _changed(() => _weekdays = weekdays),
              ),
            ],
            TextField(
              key: const Key('addClassStartTime'),
              controller: _startTimeController,
              enabled: !_saving,
              keyboardType: TextInputType.datetime,
              inputFormatters: const [TimeInputFormatter()],
              decoration: InputDecoration(
                labelText: l10n.addClassStartTime,
                hintText: '18:00',
                errorText: _startTime == null
                    ? l10n.addClassStartTimeInvalid
                    : null,
                prefixIcon: const Icon(Icons.schedule),
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => _changed(() {}),
            ),
            TextField(
              key: const Key('addClassDuration'),
              controller: _durationController,
              enabled: !_saving,
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: l10n.addClassDuration,
                errorText: _duration == null
                    ? l10n.addClassDurationInvalid
                    : null,
                prefixIcon: const Icon(Icons.timelapse),
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => _changed(() {}),
            ),
            const Divider(),
            PlanPreviewSection(
              complete: _plan != null,
              loading: _previewLoading,
              preview: preview,
              error: _previewError,
              recurring: _repeat == ClassRepeat.weekly,
              onToggleExcluded: _saving ? null : _toggleExcluded,
              onRetry: _schedulePreview,
            ),
            if (_saveError case final error?)
              Text(
                planErrorMessage(error, l10n, saving: true),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            FilledButton.icon(
              key: const Key('addClassSave'),
              onPressed: _canSave ? _save : null,
              icon: _saving
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Theme.of(context).colorScheme.onPrimary,
                      ),
                    )
                  : const Icon(Icons.check),
              label: Text(
                _repeat == ClassRepeat.weekly && preview != null
                    ? l10n.addClassSaveSeries(
                        preview.occurrences.where((o) => !o.excluded).length,
                      )
                    : l10n.addClassSave,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickDay({required bool start}) async {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final first = start ? today : _day;
    final current = start ? _day : _endsOn;
    final picked = await showDatePicker(
      context: context,
      initialDate: current.isBefore(first) ? first : current,
      firstDate: first,
      lastDate: DateTime(today.year + 1, today.month, today.day),
    );
    if (picked == null) {
      return;
    }
    _changed(() {
      if (start) {
        _day = picked;
        if (_repeat == ClassRepeat.once) {
          _weekdays = {picked.weekday};
        }
        if (!_endsOn.isAfter(picked)) {
          _endsOn = DateTime(picked.year, picked.month + 1, picked.day);
        }
      } else {
        _endsOn = picked;
      }
    });
  }
}

/// A form row that opens a picker.
class _PickerTile extends StatelessWidget {
  const _PickerTile({
    super.key,
    required this.icon,
    required this.label,
    required this.value,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(4),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: label,
          prefixIcon: Icon(icon),
          border: const OutlineInputBorder(),
          enabled: onTap != null,
        ),
        child: Text(value),
      ),
    );
  }
}

/// Chips for the weekdays a recurring class takes place on.
class _WeekdayPicker extends StatelessWidget {
  const _WeekdayPicker({
    required this.selected,
    required this.enabled,
    required this.onChanged,
  });

  final Set<int> selected;
  final bool enabled;
  final ValueChanged<Set<int>> onChanged;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 2024-01-01 was a Monday.
    final names = [
      for (var day = 1; day <= 7; day++)
        DateFormat('EEE', 'pl').format(DateTime(2024, 1, day)),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 8,
      children: [
        Text(
          l10n.addClassWeekdays,
          style: Theme.of(context).textTheme.labelLarge,
        ),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (var weekday = 1; weekday <= 7; weekday++)
              FilterChip(
                key: Key('addClassWeekday$weekday'),
                label: Text(names[weekday - 1]),
                selected: selected.contains(weekday),
                onSelected: enabled
                    ? (on) => onChanged(
                        on
                            ? {...selected, weekday}
                            : ({...selected}..remove(weekday)),
                      )
                    : null,
              ),
          ],
        ),
        if (selected.isEmpty)
          Text(
            l10n.addClassWeekdaysRequired,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}
