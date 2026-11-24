import 'dart:async';

import 'package:mobile/features/trainings/data/group_classes_api.dart';

import 'group_class_fixtures.dart';

/// A group classes API over one class ([data]) and [extraClasses], which
/// records what the screens send.
class FakeGroupClassesApi implements GroupClassesApi {
  FakeGroupClassesApi({Map<String, dynamic>? data})
    : data = data ?? groupClassJson();

  Map<String, dynamic> data;
  bool fails = false;
  bool empty = false;
  bool cancellationConflict = false;
  bool cancellationReturned = true;
  Completer<List<GroupClass>>? pendingSelf;
  String? bookedPassId;
  ClassQuery? bookedQuery;
  int cancellations = 0;
  bool? expectedEntryReturned;
  int reservationReads = 0;
  final List<String> reads = [];
  final List<ClassesQuery> queries = [];
  final List<Map<String, dynamic>> extraClasses = [];
  int participantReads = 0;
  bool participantsFail = false;
  int classReads = 0;
  String? joinedPassId;
  ClassQuery? joinedQuery;

  /// The last place of the class frees up just before the join arrives.
  bool placeFreedBeforeJoin = false;
  String? leftEntryId;
  BookingScope? leftScope;
  String? confirmedEntryId;

  @override
  Future<List<ClassParticipant>> fetchParticipants(ClassQuery query) async {
    participantReads++;
    if (participantsFail) throw const ClassBookingException(statusCode: 403);
    return const [
      ClassParticipant(displayName: 'Anna Klient', isSelf: true),
      ClassParticipant(displayName: 'Maria Nowak', isSelf: false),
    ];
  }

  @override
  Future<List<GroupClass>> fetchClasses(ClassesQuery query) async {
    reads.add(query.scope.profileId);
    queries.add(query);
    if (fails) throw const ClassBookingException(statusCode: 503);
    if (query.scope.profileId == 'self' && pendingSelf != null) {
      return pendingSelf!.future;
    }
    if (empty) return [];
    return [
          for (final item in [data, ...extraClasses])
            GroupClass.fromJson({
              ...item,
              if (query.scope.profileId == 'child')
                'title': 'Zajęcia dziecięce',
            }),
        ]
        .where(
          (event) =>
              event.startsAt.isBefore(query.window.to) &&
              event.endsAt.isAfter(query.window.from),
        )
        .toList();
  }

  @override
  Future<List<GroupClass>> fetchReservations(ClassesQuery query) async {
    reservationReads++;
    return (await fetchClasses(
      query,
    )).where((event) => event.isReserved).toList();
  }

  @override
  Future<GroupClass> fetchClass(ClassQuery query) async {
    classReads++;
    if (fails) throw const ClassBookingException(statusCode: 503);
    return GroupClass.fromJson(data);
  }

  @override
  Future<ClassWaitlistEntry> joinWaitlist(
    ClassQuery query,
    String customerPassId,
  ) async {
    joinedPassId = customerPassId;
    joinedQuery = query;
    if (placeFreedBeforeJoin) {
      data = groupClassJson();
      throw const ClassBookingException(
        statusCode: 409,
        code: 'CLASS_NOT_FULL',
      );
    }
    data = fullClassJson(waitlistPosition: 3);
    return ClassWaitlistEntry.fromJson(waitlistEntryJson(position: 3));
  }

  @override
  Future<ClassWaitlistEntry> confirmWaitlistOffer(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async {
    confirmedEntryId = entry.id;
    data = groupClassJson(reserved: true);
    return ClassWaitlistEntry.fromJson({
      ...waitlistEntryJson(waiting: false),
      'status': 'PROMOTED',
      'reservationId': 'reservation-1',
    });
  }

  @override
  Future<ClassWaitlistEntry> leaveWaitlist(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async {
    leftScope = scope;
    leftEntryId = entry.id;
    data = fullClassJson(waitingCount: 2);
    return ClassWaitlistEntry.fromJson(waitlistEntryJson(waiting: false));
  }

  @override
  Future<ClassReservation> reserve(
    ClassQuery query,
    String customerPassId,
  ) async {
    bookedPassId = customerPassId;
    bookedQuery = query;
    data = groupClassJson(reserved: true);
    return ClassReservation.fromJson(reservationJson());
  }

  @override
  Future<ClassReservation> cancel(
    BookingScope scope,
    ClassReservation reservation,
  ) async {
    cancellations++;
    expectedEntryReturned = reservation.cancellation.entryReturned;
    if (cancellationConflict) {
      cancellationConflict = false;
      data = groupClassJson(reserved: true, entryReturned: false);
      throw const ClassBookingException(
        statusCode: 409,
        code: 'CANCELLATION_CONSEQUENCE_CHANGED',
      );
    }
    data = groupClassJson();
    return ClassReservation.fromJson(
      reservationJson(active: false, entryReturned: cancellationReturned),
    );
  }
}
