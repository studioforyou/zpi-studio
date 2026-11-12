import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// Account is part of every cache key, even when profiles have distinct ids.
typedef BookingScope = ({String accountId, String profileId});
typedef ClassesQuery = ({BookingScope scope, ScheduleWindow window});
typedef ClassQuery = ({BookingScope scope, String eventId});

String _profilePath(String profileId) => '/api/v1/me/profiles/$profileId';

class EligibleClassPass {
  const EligibleClassPass({
    required this.id,
    required this.productName,
    required this.entriesRemaining,
    required this.validUntil,
  });

  factory EligibleClassPass.fromJson(Map<String, dynamic> json) =>
      EligibleClassPass(
        id: json['id'] as String,
        productName: json['productName'] as String,
        entriesRemaining: json['entriesRemaining'] as int?,
        validUntil: _date(json['validUntil']),
      );

  final String id;
  final String productName;
  final int? entriesRemaining;
  final DateTime? validUntil;
}

class ClassBooking {
  const ClassBooking({
    required this.allowed,
    required this.reason,
    required this.eligiblePasses,
  });

  factory ClassBooking.fromJson(Map<String, dynamic> json) => ClassBooking(
    allowed: json['allowed'] as bool,
    reason: json['reason'] as String?,
    eligiblePasses: [
      for (final item in json['eligiblePasses'] as List)
        EligibleClassPass.fromJson(item as Map<String, dynamic>),
    ],
  );

  final bool allowed;
  final String? reason;
  final List<EligibleClassPass> eligiblePasses;
}

class ClassCancellation {
  const ClassCancellation({
    required this.allowed,
    required this.reason,
    required this.entryReturned,
  });

  factory ClassCancellation.fromJson(Map<String, dynamic> json) =>
      ClassCancellation(
        allowed: json['allowed'] as bool,
        reason: json['reason'] as String?,
        entryReturned: json['entryReturned'] as bool?,
      );

  final bool allowed;
  final String? reason;

  /// Current forecast. The server checks this again when cancelling.
  final bool? entryReturned;
}

class ClassReservation {
  const ClassReservation({
    required this.id,
    required this.eventId,
    required this.customerId,
    required this.status,
    required this.customerPassId,
    required this.freeCancelUntil,
    required this.entryReturned,
    required this.cancellation,
    required this.version,
    this.makeupCreditId,
  });

  factory ClassReservation.fromJson(Map<String, dynamic> json) =>
      ClassReservation(
        id: json['id'] as String,
        eventId: json['eventId'] as String,
        customerId: json['customerId'] as String,
        status: json['status'] as String,
        customerPassId: json['customerPassId'] as String?,
        freeCancelUntil: _date(json['freeCancelUntil']),
        entryReturned: json['entryReturned'] as bool?,
        cancellation: ClassCancellation.fromJson(
          json['cancellation'] as Map<String, dynamic>,
        ),
        version: json['version'] as int,
        makeupCreditId: json['makeupCreditId'] as String?,
      );

  final String id;
  final String eventId;
  final String customerId;
  final String status;
  final String? customerPassId;
  final DateTime? freeCancelUntil;
  final bool? entryReturned;
  final ClassCancellation cancellation;
  final int version;
  final String? makeupCreditId;

  bool get isActive => status == 'ACTIVE';
}

/// The selected participant's place in a class's waiting list. Joining
/// charges nothing; the server books the participant automatically when a
/// place frees up.
class ClassWaitlistEntry {
  const ClassWaitlistEntry({
    required this.id,
    required this.eventId,
    required this.customerId,
    required this.status,
    required this.customerPassId,
    required this.joinedAt,
    required this.position,
    required this.promotionBlockedReason,
    this.offerExpiresAt,
  });

  factory ClassWaitlistEntry.fromJson(Map<String, dynamic> json) =>
      ClassWaitlistEntry(
        id: json['id'] as String,
        eventId: json['eventId'] as String,
        customerId: json['customerId'] as String,
        status: json['status'] as String,
        customerPassId: json['customerPassId'] as String?,
        joinedAt: DateTime.parse(json['joinedAt'] as String).toLocal(),
        position: json['position'] as int?,
        promotionBlockedReason: json['promotionBlockedReason'] as String?,
        offerExpiresAt: _date(json['offerExpiresAt']),
      );

  final String id;
  final String eventId;
  final String customerId;
  final String status;

  /// The pass chosen when joining; promotion may use another eligible one.
  final String? customerPassId;
  final DateTime joinedAt;

  /// 1-based place in the queue; `null` once the entry is no longer waiting.
  final int? position;

  /// Why the participant would be skipped if a place freed up now, or why
  /// an offered place could not be confirmed now.
  final String? promotionBlockedReason;

  /// Until when an offered place can be confirmed; `null` unless offered.
  final DateTime? offerExpiresAt;

  bool get isWaiting => status == 'WAITING';

  /// A freed place is held for the participant until [offerExpiresAt].
  bool get isOffered => status == 'OFFERED';

  /// Still on the list: waiting in the queue or holding an offered place.
  bool get isOpen => isWaiting || isOffered;
}

class ClassWaitlist {
  const ClassWaitlist({
    required this.allowed,
    required this.reason,
    required this.waitingCount,
    required this.ownEntry,
    this.confirmationMinutes = 0,
  });

  factory ClassWaitlist.fromJson(Map<String, dynamic> json) => ClassWaitlist(
    allowed: json['allowed'] as bool,
    reason: json['reason'] as String?,
    waitingCount: json['waitingCount'] as int,
    confirmationMinutes: json['confirmationMinutes'] as int? ?? 0,
    ownEntry: json['ownEntry'] == null
        ? null
        : ClassWaitlistEntry.fromJson(json['ownEntry'] as Map<String, dynamic>),
  );

  /// A class without the waiting list in its JSON (an older server) behaves
  /// as if the list were disabled, so a full class keeps its old screen.
  static const unavailable = ClassWaitlist(
    allowed: false,
    reason: 'WAITLIST_DISABLED',
    waitingCount: 0,
    ownEntry: null,
  );

  /// Whether the selected participant can join now.
  final bool allowed;
  final String? reason;

  /// Participants waiting for a place, including the selected one.
  final int waitingCount;

  /// The selected participant's waiting entry, if any.
  final ClassWaitlistEntry? ownEntry;

  /// Minutes to confirm a place offered from the list; 0 books it at once.
  final int confirmationMinutes;
}

class GroupClass {
  const GroupClass({
    required this.id,
    required this.title,
    required this.serviceTypeName,
    required this.startsAt,
    required this.endsAt,
    required this.status,
    required this.capacity,
    required this.activeReservations,
    required this.availablePlaces,
    required this.staff,
    required this.roomNames,
    required this.ownReservation,
    required this.booking,
    this.waitlist = ClassWaitlist.unavailable,
    this.calendarColor,
    this.serviceTypeId,
    this.seriesChange,
  });

  factory GroupClass.fromJson(Map<String, dynamic> json) => GroupClass(
    id: json['id'] as String,
    title: json['title'] as String?,
    serviceTypeName: json['serviceTypeName'] as String?,
    serviceTypeId: json['serviceTypeId'] as String?,
    calendarColor: json['calendarColor'] as String?,
    startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
    endsAt: DateTime.parse(json['endsAt'] as String).toLocal(),
    status: json['status'] as String,
    capacity: json['capacity'] as int?,
    activeReservations: json['activeReservations'] as int,
    availablePlaces: json['availablePlaces'] as int?,
    staff: [
      for (final item in json['staff'] as List)
        EventStaff.fromJson(item as Map<String, dynamic>),
    ],
    roomNames: [
      for (final item in json['resources'] as List)
        (item as Map<String, dynamic>)['name'] as String,
    ],
    ownReservation: json['ownReservation'] == null
        ? null
        : ClassReservation.fromJson(
            json['ownReservation'] as Map<String, dynamic>,
          ),
    booking: ClassBooking.fromJson(json['booking'] as Map<String, dynamic>),
    waitlist: json['waitlist'] == null
        ? ClassWaitlist.unavailable
        : ClassWaitlist.fromJson(json['waitlist'] as Map<String, dynamic>),
    seriesChange: SeriesChange.fromJson(json['seriesChange']),
  );

  final String id;
  final String? title;
  final String? serviceTypeName;
  final String? serviceTypeId;
  final String? calendarColor;

  /// How a one-off change makes this class differ from its series, or `null`.
  final SeriesChange? seriesChange;
  final DateTime startsAt;
  final DateTime endsAt;
  final String status;
  final int? capacity;
  final int activeReservations;
  final int? availablePlaces;
  final List<EventStaff> staff;
  final List<String> roomNames;
  final ClassReservation? ownReservation;
  final ClassBooking booking;
  final ClassWaitlist waitlist;

  String? get name => title ?? serviceTypeName;
  bool get isReserved => ownReservation?.isActive ?? false;
  bool get isWaitlisted => waitlist.ownEntry?.isOpen ?? false;
  bool get isFull => availablePlaces != null && availablePlaces! <= 0;
}

class ClassParticipant {
  const ClassParticipant({required this.displayName, required this.isSelf});

  factory ClassParticipant.fromJson(Map<String, dynamic> json) =>
      ClassParticipant(
        displayName: json['displayName'] as String,
        isSelf: json['isSelf'] as bool,
      );

  final String displayName;
  final bool isSelf;
}

/// Stable reason codes are translated by the client; server text is never UI.
class ClassBookingException extends ApiException {
  const ClassBookingException({super.statusCode, this.code})
    : super(message: 'Group class request failed');

  final String? code;
}

class GroupClassesApi {
  const GroupClassesApi(this._dio);

  final Dio _dio;

  Future<List<ClassParticipant>> fetchParticipants(ClassQuery query) async {
    final data = await _request(
      () => _dio.get<Map<String, dynamic>>(
        '${_profilePath(query.scope.profileId)}/group-classes/${query.eventId}/participants',
      ),
    );
    return [
      for (final item in data['items'] as List)
        ClassParticipant.fromJson(item as Map<String, dynamic>),
    ];
  }

  Future<List<GroupClass>> fetchClasses(ClassesQuery query) =>
      _list(query, 'group-classes');

  Future<List<GroupClass>> fetchReservations(ClassesQuery query) =>
      _list(query, 'reservations');

  Future<List<GroupClass>> _list(ClassesQuery query, String resource) async {
    final data = await _request(
      () => _dio.get<Map<String, dynamic>>(
        '${_profilePath(query.scope.profileId)}/$resource',
        queryParameters: {
          'from': query.window.from.toUtc().toIso8601String(),
          'to': query.window.to.toUtc().toIso8601String(),
        },
      ),
    );
    return [
      for (final item in data['items'] as List)
        GroupClass.fromJson(item as Map<String, dynamic>),
    ];
  }

  Future<GroupClass> fetchClass(ClassQuery query) async => GroupClass.fromJson(
    await _request(
      () => _dio.get<Map<String, dynamic>>(
        '${_profilePath(query.scope.profileId)}/group-classes/${query.eventId}',
      ),
    ),
  );

  Future<ClassReservation> reserve(
    ClassQuery query,
    String customerPassId,
  ) async => ClassReservation.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_profilePath(query.scope.profileId)}/group-classes/'
        '${query.eventId}/reservations',
        data: {'customerPassId': customerPassId},
      ),
    ),
  );

  Future<ClassReservation> cancel(
    BookingScope scope,
    ClassReservation reservation,
  ) async => ClassReservation.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_profilePath(scope.profileId)}/reservations/${reservation.id}/cancel',
        data: {
          'expectedVersion': reservation.version,
          'expectedEntryReturned': reservation.cancellation.entryReturned,
        },
      ),
    ),
  );

  /// Joins the waiting list of a full class. The pass is charged only when
  /// a place is given; joining again returns the existing entry.
  Future<ClassWaitlistEntry> joinWaitlist(
    ClassQuery query,
    String customerPassId,
  ) async => ClassWaitlistEntry.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_profilePath(query.scope.profileId)}/group-classes/'
        '${query.eventId}/waitlist-entries',
        data: {'customerPassId': customerPassId},
      ),
    ),
  );

  /// Books the place offered from the waiting list with the pass chosen
  /// when joining (or another eligible one if that cannot be used).
  Future<ClassWaitlistEntry> confirmWaitlistOffer(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async => ClassWaitlistEntry.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_profilePath(scope.profileId)}/waitlist-entries/${entry.id}/confirm',
      ),
    ),
  );

  /// Leaves the queue, or declines an offered place so it goes to the next
  /// participant.
  Future<ClassWaitlistEntry> leaveWaitlist(
    BookingScope scope,
    ClassWaitlistEntry entry,
  ) async => ClassWaitlistEntry.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_profilePath(scope.profileId)}/waitlist-entries/${entry.id}/cancel',
      ),
    ),
  );

  Future<Map<String, dynamic>> _request(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async {
    try {
      return (await send()).data!;
    } on DioException catch (e) {
      final body = e.response?.data;
      throw ClassBookingException(
        statusCode: e.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

DateTime? _date(Object? value) =>
    value == null ? null : DateTime.parse(value as String).toLocal();

final groupClassesApiProvider = Provider<GroupClassesApi>(
  (ref) => GroupClassesApi(ref.watch(dioProvider)),
);

final groupClassesProvider = FutureProvider.autoDispose
    .family<List<GroupClass>, ClassesQuery>(
      (ref, query) => ref.watch(groupClassesApiProvider).fetchClasses(query),
    );

final classReservationsProvider = FutureProvider.autoDispose
    .family<List<GroupClass>, ClassesQuery>(
      (ref, query) =>
          ref.watch(groupClassesApiProvider).fetchReservations(query),
    );

final groupClassProvider = FutureProvider.autoDispose
    .family<GroupClass, ClassQuery>(
      (ref, query) => ref.watch(groupClassesApiProvider).fetchClass(query),
    );

final classParticipantsProvider = FutureProvider.autoDispose
    .family<List<ClassParticipant>, ClassQuery>(
      (ref, query) =>
          ref.watch(groupClassesApiProvider).fetchParticipants(query),
    );
