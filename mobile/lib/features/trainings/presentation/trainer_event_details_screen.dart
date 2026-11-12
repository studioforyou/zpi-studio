import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_change_widgets.dart';
import 'package:mobile/features/trainings/data/event_participants_api.dart';
import 'package:mobile/features/trainings/presentation/add_class_participants_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// One of the trainer's events with its participants, to prepare for working
/// with the group, add clients to a group class before it starts, then to
/// record their attendance and that the event was held.
class TrainerEventDetailsScreen extends ConsumerWidget {
  const TrainerEventDetailsScreen({required this.eventId, super.key});

  final String eventId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context).classesDetails)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) => _Details(
          query: (accountId: session.accountId, eventId: eventId),
          staffId: session.staffProfileId,
        ),
      },
    );
  }
}

class _Details extends ConsumerWidget {
  const _Details({required this.query, required this.staffId});

  final EventParticipantsQuery query;

  /// The signed-in trainer, to tell whether they stand in for someone.
  final String? staffId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final eventProvider = ownScheduleEventProvider(query);
    return RefreshIndicator(
      onRefresh: () => _refresh(ref),
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: ref
            .watch(eventProvider)
            .when(
              skipLoadingOnRefresh: false,
              loading: () => const [
                Padding(
                  padding: EdgeInsets.all(48),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ],
              error: (_, _) => [ClassesLoadError(onRetry: () => _retry(ref))],
              data: (event) {
                return [
                  if (event.status != EventStatus.cancelled)
                    if (event.seriesChange case final change?) ...[
                      SeriesChangeCard(
                        change: change,
                        startsAt: event.startsAt,
                        endsAt: event.endsAt,
                        instructors: [_EventSummary._names(event, lead: true)],
                        rooms: event.roomNames,
                      ),
                      if (change.usualInstructors case final usual?
                          when event.staff.any(
                            (person) =>
                                person.isLead && person.staffId == staffId,
                          ))
                        Padding(
                          padding: const EdgeInsets.fromLTRB(4, 10, 4, 0),
                          child: ClassInfoLine(
                            Icons.swap_horiz,
                            AppLocalizations.of(
                              context,
                            ).changeStandIn(usual.join(', ')),
                          ),
                        ),
                      const SizedBox(height: 12),
                    ],
                  _EventSummary(event),
                  _AttendanceSection(event: event, query: query),
                ];
              },
            ),
      ),
    );
  }

  Future<void> _refresh(WidgetRef ref) async {
    try {
      await Future.wait([
        ref.refresh(ownScheduleEventProvider(query).future),
        ref.refresh(eventAttendanceProvider(query).future),
      ]);
    } catch (_) {
      // The observed providers display load failures and offer a retry.
    }
  }

  void _retry(WidgetRef ref) {
    ref.invalidate(ownScheduleEventProvider(query));
    ref.invalidate(eventAttendanceProvider(query));
  }
}

/// The event's time, place and staff, in the calendar's card style.
class _EventSummary extends StatelessWidget {
  const _EventSummary(this.event);

  final ScheduleEvent event;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final cancelled = event.status == EventStatus.cancelled;
    final time = DateFormat('HH:mm');
    final name = event.name ?? eventKindLabel(l10n, event.kind);
    final leads = _names(event, lead: true);
    final assistants = _names(event, lead: false);
    final change = cancelled ? null : event.seriesChange;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              width: 6,
              color: scheduleEventColor(event, theme.colorScheme),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  spacing: 12,
                  children: [
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 4,
                      children: [
                        ChangedValue(
                          '${time.format(event.startsAt)}–'
                          '${time.format(event.endsAt)}',
                          usual: usualTimeOf(change, event.startsAt),
                          style: theme.textTheme.labelLarge,
                        ),
                        Text(
                          name,
                          style: theme.textTheme.titleLarge?.copyWith(
                            decoration: cancelled
                                ? TextDecoration.lineThrough
                                : null,
                            color: cancelled ? theme.colorScheme.error : null,
                          ),
                        ),
                        Text(
                          [
                            eventKindLabel(l10n, event.kind),
                            if (event.serviceTypeName case final service?
                                when service != name)
                              service,
                          ].join(' · '),
                          style: theme.textTheme.bodySmall,
                        ),
                      ],
                    ),
                    ClassInfoLine(
                      Icons.calendar_today_outlined,
                      classDate(event.startsAt),
                    ),
                    ClassInfoLine(
                      Icons.person_outline,
                      '${l10n.classesInstructor}: ',
                      value: leads.isEmpty
                          ? l10n.classesInstructorPending
                          : leads,
                      usual: change?.usualInstructors?.join(', '),
                    ),
                    if (assistants.isNotEmpty)
                      ClassInfoLine(
                        Icons.people_alt_outlined,
                        '${l10n.trainerEventAssistant}: $assistants',
                      ),
                    ClassInfoLine(
                      Icons.meeting_room_outlined,
                      '${l10n.classesRoom}: ',
                      value: event.roomNames.isEmpty
                          ? l10n.classesRoomPending
                          : event.roomNames.join(', '),
                      usual: change?.usualRooms?.join(', '),
                    ),
                    if (cancelled)
                      ClassInfoLine(Icons.event_busy, l10n.eventStatusCancelled)
                    else if (event.status == EventStatus.completed)
                      ClassInfoLine(
                        Icons.event_available,
                        l10n.eventStatusCompleted,
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _names(ScheduleEvent event, {required bool lead}) => event.staff
      .where((person) => person.isLead == lead && person.displayName != null)
      .map((person) => person.displayName!)
      .join(', ');
}

/// Participants with their attendance. From the event's start until the
/// trainer's time ends, each one is marked present or absent and the event is
/// marked as held.
class _AttendanceSection extends ConsumerStatefulWidget {
  const _AttendanceSection({required this.event, required this.query});

  final ScheduleEvent event;
  final EventParticipantsQuery query;

  @override
  ConsumerState<_AttendanceSection> createState() => _AttendanceSectionState();
}

class _AttendanceSectionState extends ConsumerState<_AttendanceSection> {
  /// The reservation being saved, or [_completing] while the event is.
  String? _saving;

  static const _completing = '';

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final provider = eventAttendanceProvider(widget.query);
    final attendance = ref.watch(provider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 20),
        const Divider(),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: Text(switch (attendance) {
                AsyncData(:final value) => l10n.trainerEventParticipants(
                  value.participants.length,
                ),
                _ => l10n.classesParticipants,
              }, style: theme.textTheme.titleMedium),
            ),
            if (_canAddParticipants)
              IconButton.filledTonal(
                onPressed: _saving == null ? _addParticipants : null,
                icon: const Icon(Icons.add),
                tooltip: l10n.classBookingAdd,
              ),
          ],
        ),
        const SizedBox(height: 8),
        attendance.when(
          skipLoadingOnRefresh: false,
          loading: () => const Padding(
            padding: EdgeInsets.all(12),
            child: Center(
              child: SizedBox(
                width: 24,
                height: 24,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          ),
          error: (_, _) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.classesParticipantsError),
              TextButton.icon(
                onPressed: () => ref.invalidate(provider),
                icon: const Icon(Icons.refresh),
                label: Text(l10n.scheduleRetryButton),
              ),
            ],
          ),
          data: (sheet) => _sheet(context, sheet),
        ),
      ],
    );
  }

  /// Clients can be added to a scheduled group class until it starts; the
  /// backend has the final say.
  bool get _canAddParticipants =>
      widget.event.kind == EventKind.groupClass &&
      widget.event.status == EventStatus.scheduled &&
      DateTime.now().isBefore(widget.event.startsAt);

  void _addParticipants() {
    Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => AddClassParticipantsScreen(query: widget.query),
      ),
    );
  }

  Widget _sheet(BuildContext context, EventAttendance sheet) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final cancelled = sheet.eventStatus == EventStatus.cancelled;
    final started = !DateTime.now().isBefore(sheet.markingOpensAt);
    final note = switch (sheet) {
      _ when cancelled => null,
      EventAttendance(markingOpen: true) => l10n.attendanceOpenUntil(
        classDateTime(sheet.markingClosesAt),
      ),
      _ when !started => l10n.attendanceNotOpen,
      _ => l10n.attendanceClosed,
    };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      spacing: 8,
      children: [
        ClassOccupancy.counts(
          reserved: sheet.participants.length,
          capacity: widget.event.capacity,
        ),
        if (note != null)
          Text(
            note,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        if (started && !cancelled && sheet.participants.isNotEmpty)
          Text(
            l10n.attendanceSummary(
              sheet.count(Attendance.present),
              sheet.count(Attendance.absent),
              sheet.count(Attendance.unmarked),
            ),
            style: theme.textTheme.labelLarge,
          ),
        if (sheet.participants.isEmpty)
          Text(l10n.trainerEventParticipantsEmpty)
        else
          for (final participant in sheet.participants)
            _ParticipantTile(
              participant,
              day: widget.event.startsAt,
              editable: sheet.markingOpen && _saving == null,
              saving: _saving == participant.reservationId,
              onMark: (attendance) => _mark(participant, attendance),
            ),
        if (sheet.markingOpen && sheet.eventStatus != EventStatus.completed)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: FilledButton.icon(
              onPressed: _saving == null ? () => _complete(sheet) : null,
              icon: _saving == _completing
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.task_alt),
              label: Text(l10n.attendanceComplete),
            ),
          ),
      ],
    );
  }

  Future<void> _mark(EventParticipant participant, Attendance attendance) =>
      _save(
        participant.reservationId,
        (controller) => controller.mark(participant.reservationId, attendance),
      );

  Future<void> _complete(EventAttendance sheet) async {
    final l10n = AppLocalizations.of(context);
    final unmarked = sheet.count(Attendance.unmarked);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.attendanceCompleteTitle),
        content: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          spacing: 12,
          children: [
            if (unmarked > 0) Text(l10n.attendanceCompleteUnmarked(unmarked)),
            Text(
              l10n.attendanceCompleteChanges(
                classDateTime(sheet.markingClosesAt),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(l10n.classesDismiss),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(l10n.attendanceCompleteConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final done = await _save(
      _completing,
      (controller) => controller.complete(),
    );
    if (!done || !mounted) return;
    // The event's status and the schedule's tile show that it was held.
    ref.invalidate(ownScheduleEventProvider(widget.query));
    ref.invalidate(ownScheduleProvider);
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l10n.attendanceCompleted)));
  }

  /// Runs a change of the sheet; a rejection is explained and the sheet is
  /// loaded again, since it may be out of date.
  Future<bool> _save(
    String saving,
    Future<void> Function(EventAttendanceController controller) change,
  ) async {
    if (_saving != null) return false;
    setState(() => _saving = saving);
    final provider = eventAttendanceProvider(widget.query);
    try {
      await change(ref.read(provider.notifier));
      return true;
    } catch (error) {
      if (!mounted) return false;
      final l10n = AppLocalizations.of(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(switch (error) {
            AttendanceException(code: 'ATTENDANCE_CLOSED') =>
              l10n.attendanceClosed,
            AttendanceException(code: 'ATTENDANCE_NOT_OPEN') =>
              l10n.attendanceNotOpen,
            AttendanceException(code: 'EVENT_CANCELLED') =>
              l10n.attendanceEventCancelled,
            AttendanceException(code: 'RESERVATION_NOT_ACTIVE') =>
              l10n.attendanceReservationCancelled,
            _ => l10n.attendanceSaveFailed,
          }),
        ),
      );
      ref.invalidate(provider);
      return false;
    } finally {
      if (mounted) setState(() => _saving = null);
    }
  }
}

class _ParticipantTile extends StatelessWidget {
  const _ParticipantTile(
    this.participant, {
    required this.day,
    required this.editable,
    required this.saving,
    required this.onMark,
  });

  final EventParticipant participant;

  /// The day of the event, on which the age is given.
  final DateTime day;

  /// Whether the attendance can be changed now.
  final bool editable;

  /// Whether the participant's attendance is being saved.
  final bool saving;

  final ValueChanged<Attendance> onMark;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final age = participant.ageOn(day);
    final details = [
      if (participant.isChild) l10n.traineesChild,
      if (age != null) l10n.trainerEventParticipantAge(age),
    ];
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(child: Text(_initials(participant.displayName))),
      title: Text(participant.displayName),
      subtitle: details.isEmpty ? null : Text(details.join(' · ')),
      trailing: _attendance(context, l10n),
    );
  }

  Widget? _attendance(BuildContext context, AppLocalizations l10n) {
    if (saving) {
      return const SizedBox(
        width: 24,
        height: 24,
        child: CircularProgressIndicator(strokeWidth: 2),
      );
    }
    if (editable || participant.attendance != Attendance.unmarked) {
      return SegmentedButton<Attendance>(
        segments: [
          ButtonSegment(
            value: Attendance.present,
            icon: const Icon(Icons.check),
            tooltip: l10n.attendancePresent,
          ),
          ButtonSegment(
            value: Attendance.absent,
            icon: const Icon(Icons.close),
            tooltip: l10n.attendanceAbsent,
          ),
        ],
        selected: {
          if (participant.attendance != Attendance.unmarked)
            participant.attendance,
        },
        emptySelectionAllowed: true,
        showSelectedIcon: false,
        onSelectionChanged: editable
            ? (selection) {
                // Tapping the current value again keeps it: it cannot be cleared.
                if (selection.isNotEmpty) onMark(selection.single);
              }
            : null,
      );
    }
    return null;
  }

  static String _initials(String name) => name
      .split(' ')
      .where((part) => part.isNotEmpty)
      .take(2)
      .map((part) => part[0].toUpperCase())
      .join();
}
