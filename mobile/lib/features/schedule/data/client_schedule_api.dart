import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';

/// Booking information travels with an event through the shared calendar.
class ClientScheduleEvent extends ScheduleEvent {
  ClientScheduleEvent(this.groupClass)
    : super(
        id: groupClass.id,
        kind: EventKind.groupClass,
        status: EventStatus.fromCode(groupClass.status),
        title: groupClass.title,
        serviceTypeName: groupClass.serviceTypeName,
        serviceTypeId: groupClass.serviceTypeId,
        startsAt: groupClass.startsAt,
        endsAt: groupClass.endsAt,
        capacity: groupClass.capacity,
        staff: groupClass.staff,
        roomNames: groupClass.roomNames,
        seriesChange: groupClass.seriesChange,
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
      final classesFuture = ref.watch(provider.future);
      final personalFuture = query.reservationsOnly
          ? ref.watch(personalTrainingsProvider(bookingQuery).future)
          : Future.value(const <PersonalSession>[]);
      final results = await Future.wait<List<ScheduleEvent>>([
        classesFuture.then(
          (classes) => [
            for (final event in classes) ClientScheduleEvent(event),
          ],
        ),
        personalFuture,
      ]);
      return results.expand((events) => events).toList()
        ..sort((a, b) => a.startsAt.compareTo(b.startsAt));
    });

/// An event can be reserved by both parent and child. Keep both reservations
/// distinct, with the participant and scope needed to open the correct details.
class ProfileScheduleEvent extends ScheduleEvent {
  ProfileScheduleEvent({
    required this.event,
    required this.profile,
    required this.scope,
  }) : super(
         id: '${profile.id}:${event.kind.name}:${event.id}',
         kind: event.kind,
         status: event.status,
         title: event.title,
         serviceTypeName: event.serviceTypeName,
         serviceTypeId: event.serviceTypeId,
         startsAt: event.startsAt,
         endsAt: event.endsAt,
         capacity: event.capacity,
         staff: event.staff,
         roomNames: event.roomNames,
         seriesChange: event.seriesChange,
       );
  final ScheduleEvent event;
  final Profile profile;
  final BookingScope scope;
}

final familyScheduleProvider = FutureProvider.autoDispose
    .family<List<ScheduleEvent>, ({String accountId, ScheduleWindow window})>((
      ref,
      query,
    ) async {
      final session = clientSession(ref.watch(authControllerProvider));
      if (session == null || session.accountId != query.accountId) {
        throw const ClassBookingException(statusCode: 403);
      }
      final results = await Future.wait([
        for (final profile in session.profiles)
          ref
              .watch(
                clientScheduleProvider((
                  scope: (accountId: session.accountId, profileId: profile.id),
                  window: query.window,
                  reservationsOnly: true,
                )).future,
              )
              .then(
                (events) => [
                  for (final event in events)
                    ProfileScheduleEvent(
                      event: event,
                      profile: profile,
                      scope: (
                        accountId: session.accountId,
                        profileId: profile.id,
                      ),
                    ),
                ],
              ),
      ]);
      return results.expand<ScheduleEvent>((events) => events).toList()
        ..sort((a, b) {
          final time = a.startsAt.compareTo(b.startsAt);
          return time == 0 ? a.id.compareTo(b.id) : time;
        });
    });
