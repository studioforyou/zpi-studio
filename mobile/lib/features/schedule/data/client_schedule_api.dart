import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

/// Booking information travels with an event through the shared calendar.
class ClientScheduleEvent extends ScheduleEvent {
  ClientScheduleEvent(this.groupClass)
    : super(
        id: groupClass.id,
        kind: EventKind.groupClass,
        status: EventStatus.fromCode(groupClass.status),
        title: groupClass.title,
        serviceTypeName: groupClass.serviceTypeName,
        startsAt: groupClass.startsAt,
        endsAt: groupClass.endsAt,
        capacity: groupClass.capacity,
        staff: groupClass.staff,
        roomNames: groupClass.roomNames,
      );

  final GroupClass groupClass;
}

typedef ClientScheduleQuery = ({
  BookingScope scope,
  ScheduleWindow window,
  bool reservationsOnly,
});

/// The booking providers remain the source of truth. Their invalidation after
/// booking/cancellation also refreshes every visible calendar view.
final clientScheduleProvider = FutureProvider.autoDispose
    .family<List<ScheduleEvent>, ClientScheduleQuery>((ref, query) async {
      final bookingQuery = (scope: query.scope, window: query.window);
      final provider = query.reservationsOnly
          ? classReservationsProvider(bookingQuery)
          : groupClassesProvider(bookingQuery);
      final classes = await ref.watch(provider.future);
      return [for (final event in classes) ClientScheduleEvent(event)];
    });
