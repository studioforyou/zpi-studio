import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/reschedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/plan_messages.dart';
import 'package:mobile/features/schedule/presentation/time_input.dart';
import 'package:mobile/features/schedule/presentation/widgets/plan_preview_section.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Whether the signed-in trainer [staffId] may move [event] to another time:
/// a scheduled class, workshop or personal training they lead that has not
/// started yet. The backend has the final say.
bool canReschedule(ScheduleEvent event, String? staffId, {DateTime? now}) =>
    staffId != null &&
    event.status == EventStatus.scheduled &&
    const {
      EventKind.groupClass,
      EventKind.personalTraining,
      EventKind.workshop,
    }.contains(event.kind) &&
    event.staff.any((person) => person.isLead && person.staffId == staffId) &&
    (now ?? DateTime.now()).isBefore(event.startsAt);

/// Moves one occurrence the trainer leads to another day, time, length or
/// room. Every change is checked against the schedule before it can be
/// saved; saving asks for a confirmation and returns the moved event.
class RescheduleEventScreen extends ConsumerStatefulWidget {
  const RescheduleEventScreen({
    required this.event,
    this.participantCount,
    super.key,
  });

  final ScheduleEvent event;

  /// How many participants will be told, when known.
  final int? participantCount;

  @override
  ConsumerState<RescheduleEventScreen> createState() =>
      _RescheduleEventScreenState();
}

class _RescheduleEventScreenState extends ConsumerState<RescheduleEventScreen> {
  late final TextEditingController _time;
  late final TextEditingController _duration;
  late DateTime _day;

  /// The chosen room, or `null` to keep the event's rooms.
  String? _roomId;
  Timer? _timer;
  int _revision = 0;
  bool _loading = false;
  bool _saving = false;
  PlanPreview? _preview;
  Object? _error;
  Object? _saveError;

  ScheduleEvent get _event => widget.event;

  @override
  void initState() {
    super.initState();
    final start = _event.startsAt;
    _day = DateTime(start.year, start.month, start.day);
    _time = TextEditingController(text: DateFormat('HH:mm').format(start));
    _duration = TextEditingController(
      text: '${_event.endsAt.difference(start).inMinutes}',
    );
    _roomId = _event.roomIds.length == 1 ? _event.roomIds.single : null;
  }

  @override
  void dispose() {
    _timer?.cancel();
    _time.dispose();
    _duration.dispose();
    super.dispose();
  }

  int? get _minutes {
    final minutes = int.tryParse(_duration.text);
    return minutes != null && minutes >= 5 && minutes <= 720 ? minutes : null;
  }

  ReschedulePlan? get _plan {
    final time = parseTime(_time.text);
    final minutes = _minutes;
    if (time == null || minutes == null) return null;
    final start = DateTime(
      _day.year,
      _day.month,
      _day.day,
      time.hour,
      time.minute,
    );
    final room = _roomId;
    final keepRooms =
        room == null ||
        (_event.roomIds.length == 1 && _event.roomIds.single == room);
    return ReschedulePlan(
      version: _event.eventVersion,
      startsAt: start,
      endsAt: start.add(Duration(minutes: minutes)),
      roomId: keepRooms ? null : room,
    );
  }

  bool _unchanged(ReschedulePlan plan) =>
      plan.roomId == null &&
      plan.startsAt.isAtSameMomentAs(_event.startsAt) &&
      plan.endsAt.isAtSameMomentAs(_event.endsAt);

  void _changed(VoidCallback change) {
    _timer?.cancel();
    // Invalidate in-flight responses immediately, including during the debounce.
    final revision = ++_revision;
    setState(() {
      change();
      _preview = null;
      _error = null;
      _saveError = null;
      final plan = _plan;
      _loading = plan != null && !_unchanged(plan);
    });
    final plan = _plan;
    if (plan != null && !_unchanged(plan)) {
      _timer = Timer(
        const Duration(milliseconds: 400),
        () => _check(plan, revision),
      );
    }
  }

  Future<void> _check(ReschedulePlan plan, int revision) async {
    try {
      final preview = await ref
          .read(rescheduleApiProvider)
          .preview(_event.id, plan);
      if (!mounted || revision != _revision) return;
      setState(() {
        _preview = preview;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || revision != _revision) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  bool get _canSave {
    final plan = _plan;
    final preview = _preview;
    return !_saving &&
        !_loading &&
        plan != null &&
        !_unchanged(plan) &&
        preview != null &&
        preview.conflictCount == 0 &&
        preview.occurrences.isNotEmpty &&
        !preview.occurrences.any((o) => o.excluded || o.hasBlockingIssue);
  }

  Future<void> _save(List<PlanningRoom> rooms) async {
    final plan = _plan;
    if (!_canSave || plan == null) return;
    final confirmed = await _confirm(plan, rooms);
    if (!mounted || confirmed != true) return;
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      final event = await ref
          .read(rescheduleApiProvider)
          .reschedule(_event.id, plan);
      if (!mounted) return;
      ref.invalidate(ownScheduleProvider);
      Navigator.of(context).pop(event);
    } catch (e) {
      if (!mounted) return;
      final stale =
          e is PlanRejectedException &&
          (e.code == 'VERSION_CONFLICT' || e.code == 'EVENT_NOT_EDITABLE');
      setState(() {
        _saving = false;
        _saveError = e;
        _preview = null;
        _loading = !stale;
      });
      // A free preview does not reserve the slot; recheck after a concurrent
      // change, unless the event itself changed and must be reloaded first.
      if (!stale) await _check(plan, ++_revision);
    }
  }

  Future<bool?> _confirm(ReschedulePlan plan, List<PlanningRoom> rooms) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final roomName = plan.roomId == null
        ? _event.roomNames.join(', ')
        : rooms
              .where((room) => room.id == plan.roomId)
              .map((room) => room.name)
              .firstOrNull;
    final count = widget.participantCount;
    return showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.rescheduleConfirmTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 8,
          children: [
            if (_event.name case final name?)
              Text(name, style: theme.textTheme.titleSmall),
            Text(
              _when(
                _event.startsAt,
                _event.endsAt,
                _event.roomNames.join(', '),
              ),
              style: theme.textTheme.bodyMedium?.copyWith(
                decoration: TextDecoration.lineThrough,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            Text(
              _when(plan.startsAt, plan.endsAt, roomName),
              key: const Key('rescheduleConfirmNew'),
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            Text(
              count == null
                  ? l10n.rescheduleConfirmNotify
                  : l10n.rescheduleConfirmParticipants(count),
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.rescheduleConfirmBack),
          ),
          TextButton(
            key: const Key('rescheduleConfirm'),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.rescheduleConfirmAction),
          ),
        ],
      ),
    );
  }

  static String _when(DateTime start, DateTime end, String? room) {
    final time = DateFormat('HH:mm');
    return [
      '${DateFormat('EEE d.MM', 'pl').format(start)}, '
          '${time.format(start)}–${time.format(end)}',
      if (room != null && room.isNotEmpty) room,
    ].join(', ');
  }

  Future<void> _pickDay() async {
    final now = DateTime.now();
    final day = await showDatePicker(
      context: context,
      initialDate: _day.isBefore(DateTime(now.year, now.month, now.day))
          ? now
          : _day,
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: DateTime(now.year + 2, now.month, now.day),
    );
    if (mounted && day != null) _changed(() => _day = day);
  }

  String _message(Object error, AppLocalizations l10n) {
    if (error is PlanRejectedException) {
      switch (error.code) {
        case 'VERSION_CONFLICT':
          return l10n.rescheduleVersionConflict;
        case 'EVENT_NOT_EDITABLE':
          return l10n.rescheduleNotEditable;
      }
    }
    if (error is ApiException && error.statusCode == 404) {
      return l10n.rescheduleNotFound;
    }
    return planErrorMessage(error, l10n, saving: _saveError != null);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final rooms = ref.watch(planningRoomsProvider);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.rescheduleTitle)),
      body: rooms.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(l10n.addClassLoadError),
              TextButton.icon(
                onPressed: () => ref.invalidate(planningRoomsProvider),
                icon: const Icon(Icons.refresh),
                label: Text(l10n.scheduleRetryButton),
              ),
            ],
          ),
        ),
        data: (rooms) => SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              spacing: 16,
              children: [
                Text(
                  [
                    ?_event.name,
                    l10n.rescheduleCurrent(
                      _when(
                        _event.startsAt,
                        _event.endsAt,
                        _event.roomNames.join(', '),
                      ),
                    ),
                  ].join(' · '),
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                Card(
                  margin: EdgeInsets.zero,
                  child: ListTile(
                    key: const Key('rescheduleDay'),
                    leading: const Icon(Icons.calendar_today),
                    title: Text(
                      DateFormat('EEEE, d MMMM y', 'pl').format(_day),
                    ),
                    trailing: const Icon(Icons.edit_calendar_outlined),
                    onTap: _saving ? null : _pickDay,
                  ),
                ),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 12,
                  children: [
                    Expanded(
                      child: TextField(
                        key: const Key('rescheduleTime'),
                        controller: _time,
                        enabled: !_saving,
                        keyboardType: TextInputType.datetime,
                        inputFormatters: const [TimeInputFormatter()],
                        decoration: InputDecoration(
                          labelText: l10n.addClassStartTime,
                          errorText: parseTime(_time.text) == null
                              ? l10n.addClassStartTimeInvalid
                              : null,
                          errorMaxLines: 3,
                          prefixIcon: const Icon(Icons.schedule),
                          border: const OutlineInputBorder(),
                        ),
                        onChanged: (_) => _changed(() {}),
                      ),
                    ),
                    Expanded(
                      child: TextField(
                        key: const Key('rescheduleDuration'),
                        controller: _duration,
                        enabled: !_saving,
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],
                        decoration: InputDecoration(
                          labelText: l10n.addClassDuration,
                          errorText: _minutes == null
                              ? l10n.addClassDurationInvalid
                              : null,
                          errorMaxLines: 3,
                          prefixIcon: const Icon(Icons.timelapse),
                          border: const OutlineInputBorder(),
                        ),
                        onChanged: (_) => _changed(() {}),
                      ),
                    ),
                  ],
                ),
                if (rooms.isNotEmpty)
                  DropdownButtonFormField<String>(
                    key: const Key('rescheduleRoom'),
                    initialValue: rooms.any((r) => r.id == _roomId)
                        ? _roomId
                        : null,
                    isExpanded: true,
                    hint: Text(
                      _event.roomNames.join(', '),
                      overflow: TextOverflow.ellipsis,
                    ),
                    decoration: InputDecoration(
                      labelText: l10n.addClassRoom,
                      prefixIcon: const Icon(Icons.meeting_room_outlined),
                      border: const OutlineInputBorder(),
                    ),
                    items: [
                      for (final room in rooms)
                        DropdownMenuItem(
                          value: room.id,
                          child: Text(
                            room.name,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: _saving
                        ? null
                        : (id) => _changed(() => _roomId = id),
                  ),
                ..._status(l10n, theme),
                Text(l10n.rescheduleInfo, style: theme.textTheme.bodySmall),
                if (_saveError != null)
                  Text(
                    _message(_saveError!, l10n),
                    key: const Key('rescheduleSaveError'),
                    style: TextStyle(color: theme.colorScheme.error),
                  ),
                FilledButton.icon(
                  key: const Key('rescheduleSave'),
                  onPressed: _canSave ? () => _save(rooms) : null,
                  icon: _saving
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.event_available),
                  label: Text(l10n.rescheduleSave),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// The result of checking the new time, or why it cannot be checked.
  List<Widget> _status(AppLocalizations l10n, ThemeData theme) {
    final plan = _plan;
    if (plan == null) return const [];
    if (_unchanged(plan)) return [Text(l10n.rescheduleUnchanged)];
    if (_saveError != null && _preview == null && !_loading) return const [];
    if (_error case final error?) {
      return [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _message(error, l10n),
              style: TextStyle(color: theme.colorScheme.error),
            ),
            TextButton(
              onPressed: () => _changed(() {}),
              child: Text(l10n.scheduleRetryButton),
            ),
          ],
        ),
      ];
    }
    return [
      PlanPreviewSection(
        complete: true,
        loading: _loading,
        preview: _preview,
        error: null,
        recurring: false,
        onToggleExcluded: null,
        onRetry: () => _changed(() {}),
      ),
    ];
  }
}
