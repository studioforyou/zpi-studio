import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/core/auth/auth_controller.dart';

/// Whose changes: a participant's profile or the signed-in trainer.
sealed class ChangesOwner {
  const ChangesOwner();

  String get path;
}

class ParticipantChanges extends ChangesOwner {
  const ParticipantChanges(this.scope);

  final BookingScope scope;

  @override
  String get path => '/api/v1/me/profiles/${scope.profileId}/schedule-changes';

  @override
  bool operator ==(Object other) =>
      other is ParticipantChanges && other.scope == scope;

  @override
  int get hashCode => scope.hashCode;
}

class TrainerChanges extends ChangesOwner {
  const TrainerChanges(this.scope);

  final TrainerOverviewScope scope;

  @override
  String get path => '/api/v1/me/schedule-changes';

  @override
  bool operator ==(Object other) =>
      other is TrainerChanges && other.scope == scope;

  @override
  int get hashCode => scope.hashCode;
}

enum NoticeKind {
  /// The event moved or got other rooms or staff.
  changed,

  /// The trainer now works at the event.
  assigned,

  /// The trainer no longer works at the event.
  unassigned,
}

/// One saved change of an event, told to the person it concerns. Every later
/// change of the same event is a new notice.
class ScheduleChangeNotice {
  const ScheduleChangeNotice({
    required this.id,
    required this.eventId,
    required this.kind,
    required this.eventName,
    required this.previousStartsAt,
    required this.previousEndsAt,
    required this.startsAt,
    required this.endsAt,
    required this.previousRooms,
    required this.rooms,
    required this.previousLeads,
    required this.leads,
    required this.createdAt,
  });

  factory ScheduleChangeNotice.fromJson(Map<String, dynamic> json) {
    DateTime time(String key) => DateTime.parse(json[key] as String).toLocal();
    List<String> names(String key) => [
      for (final name in json[key] as List? ?? const []) name as String,
    ];
    return ScheduleChangeNotice(
      id: json['id'] as String,
      eventId: json['eventId'] as String,
      kind: switch (json['kind']) {
        'ASSIGNED' => NoticeKind.assigned,
        'UNASSIGNED' => NoticeKind.unassigned,
        _ => NoticeKind.changed,
      },
      eventName: json['eventName'] as String?,
      previousStartsAt: time('previousStartsAt'),
      previousEndsAt: time('previousEndsAt'),
      startsAt: time('startsAt'),
      endsAt: time('endsAt'),
      previousRooms: names('previousRooms'),
      rooms: names('rooms'),
      previousLeads: names('previousLeads'),
      leads: names('leads'),
      createdAt: time('createdAt'),
    );
  }

  final String id;
  final String eventId;
  final NoticeKind kind;
  final String? eventName;
  final DateTime previousStartsAt;
  final DateTime previousEndsAt;
  final DateTime startsAt;
  final DateTime endsAt;
  final List<String> previousRooms;
  final List<String> rooms;
  final List<String> previousLeads;
  final List<String> leads;
  final DateTime createdAt;

  bool get timeChanged =>
      previousStartsAt != startsAt || previousEndsAt != endsAt;

  bool get roomsChanged => !_same(previousRooms, rooms);

  bool get leadsChanged => !_same(previousLeads, leads);

  static bool _same(List<String> a, List<String> b) =>
      a.length == b.length && a.toSet().containsAll(b);
}

class ScheduleChangesApi {
  const ScheduleChangesApi(this._dio);

  final Dio _dio;

  Future<List<ScheduleChangeNotice>> fetch(ChangesOwner owner) async {
    final data = (await _dio.get<Map<String, dynamic>>(owner.path)).data!;
    return [
      for (final item in data['items'] as List)
        ScheduleChangeNotice.fromJson(item as Map<String, dynamic>),
    ];
  }

  Future<void> dismiss(ChangesOwner owner, String noticeId) =>
      _dio.post<void>('${owner.path}/$noticeId/dismiss');

  Future<void> dismissAll(ChangesOwner owner) =>
      _dio.post<void>('${owner.path}/dismiss-all');
}

final scheduleChangesApiProvider = Provider<ScheduleChangesApi>(
  (ref) => ScheduleChangesApi(ref.watch(dioProvider)),
);

/// How often the open notices are fetched again.
const scheduleChangesRefreshInterval = Duration(minutes: 1);

/// The open notices of [ChangesOwner], newest first, refreshed every
/// [scheduleChangesRefreshInterval]. A failed refresh keeps the last list.
final scheduleChangesProvider = StreamProvider.autoDispose
    .family<List<ScheduleChangeNotice>, ChangesOwner>((ref, owner) {
      final api = ref.watch(scheduleChangesApiProvider);
      final controller = StreamController<List<ScheduleChangeNotice>>();
      Future<void> load() async {
        try {
          controller.add(await api.fetch(owner));
        } catch (_) {
          // The notices are a hint; the next refresh tries again.
        }
      }

      unawaited(load());
      final timer = Timer.periodic(
        scheduleChangesRefreshInterval,
        (_) => load(),
      );
      ref.onDispose(() {
        timer.cancel();
        controller.close();
      });
      return controller.stream;
    });

/// Whose changes the signed-in user sees: the active customer profile's or
/// the trainer's.
final currentChangesOwnerProvider = Provider.autoDispose<ChangesOwner?>((ref) {
  final auth = ref.watch(authControllerProvider);
  if (bookingScope(auth) case final scope?) return ParticipantChanges(scope);
  if (trainerOverviewScope(auth) case final scope?) {
    return TrainerChanges(scope);
  }
  return null;
});

/// The open notices of the signed-in user.
final currentScheduleChangesProvider =
    Provider.autoDispose<List<ScheduleChangeNotice>>((ref) {
      final owner = ref.watch(currentChangesOwnerProvider);
      if (owner == null) return const [];
      return ref.watch(scheduleChangesProvider(owner)).value ?? const [];
    });

/// The number on the schedule tab: the notices the user has not closed yet.
int watchOpenScheduleChanges(WidgetRef ref) =>
    ref.watch(currentScheduleChangesProvider).length;

/// Closes one notice.
Future<void> dismissScheduleChange(
  WidgetRef ref,
  ChangesOwner owner,
  String noticeId,
) => _update(ref, owner, (api) => api.dismiss(owner, noticeId));

/// Closes all notices of [owner].
Future<void> dismissAllScheduleChanges(WidgetRef ref, ChangesOwner owner) =>
    _update(ref, owner, (api) => api.dismissAll(owner));

Future<void> _update(
  WidgetRef ref,
  ChangesOwner owner,
  Future<void> Function(ScheduleChangesApi api) send,
) async {
  try {
    await send(ref.read(scheduleChangesApiProvider));
  } catch (_) {
    // The notice stays; the user can close it again.
  }
  ref.invalidate(scheduleChangesProvider(owner));
}
