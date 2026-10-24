import 'package:mobile/features/schedule/data/schedule_api.dart';

/// The trainer's part in an event.
enum TrainerRoleFilter {
  /// Every event of the trainer.
  any,

  /// Events the trainer runs.
  lead,

  /// Events the trainer assists in.
  assistant,
}

/// Which of the trainer's events the schedule shows. Empty sets do not
/// filter, so the default shows everything.
class TrainerScheduleFilter {
  const TrainerScheduleFilter({
    this.kinds = const {},
    this.rooms = const {},
    this.showCancelled = true,
    this.role = TrainerRoleFilter.any,
  });

  /// Shown event kinds; every kind when empty.
  final Set<EventKind> kinds;

  /// Names of rooms an event must use one of; any room when empty.
  final Set<String> rooms;
  final bool showCancelled;
  final TrainerRoleFilter role;

  /// Number of filters that hide events, for the filter button.
  int get activeCount =>
      (kinds.isEmpty ? 0 : 1) +
      (rooms.isEmpty ? 0 : 1) +
      (showCancelled ? 0 : 1) +
      (role == TrainerRoleFilter.any ? 0 : 1);

  /// Whether [event] passes the filter for the trainer with [staffId].
  bool matches(ScheduleEvent event, String? staffId) {
    if (kinds.isNotEmpty && !kinds.contains(event.kind)) {
      return false;
    }
    if (rooms.isNotEmpty && !event.roomNames.any(rooms.contains)) {
      return false;
    }
    if (!showCancelled && event.status == EventStatus.cancelled) {
      return false;
    }
    if (role != TrainerRoleFilter.any && staffId != null) {
      final own = event.staff.where((s) => s.staffId == staffId).firstOrNull;
      if (own == null || own.isLead != (role == TrainerRoleFilter.lead)) {
        return false;
      }
    }
    return true;
  }

  TrainerScheduleFilter copyWith({
    Set<EventKind>? kinds,
    Set<String>? rooms,
    bool? showCancelled,
    TrainerRoleFilter? role,
  }) {
    return TrainerScheduleFilter(
      kinds: kinds ?? this.kinds,
      rooms: rooms ?? this.rooms,
      showCancelled: showCancelled ?? this.showCancelled,
      role: role ?? this.role,
    );
  }
}
