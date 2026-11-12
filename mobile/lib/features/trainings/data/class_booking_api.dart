import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

/// A pass of the participant that can fund the class.
class EligiblePass {
  const EligiblePass({
    required this.id,
    required this.productName,
    required this.entriesRemaining,
    required this.validUntil,
  });

  factory EligiblePass.fromJson(Map<String, dynamic> json) {
    final validUntil = json['validUntil'] as String?;
    return EligiblePass(
      id: json['id'] as String,
      productName: json['productName'] as String,
      entriesRemaining: json['entriesRemaining'] as int?,
      // A calendar date without a time zone.
      validUntil: validUntil == null ? null : DateTime.parse(validUntil),
    );
  }

  final String id;
  final String productName;

  /// Entries left, or `null` for a pass without an entry limit.
  final int? entriesRemaining;

  /// The last day of validity, or `null` without one.
  final DateTime? validUntil;
}

/// Whether the trainer can book the participant on the class, on the rules
/// of the participant's own booking (`TrainerBookingOptions` in the backend).
class BookingOptions {
  const BookingOptions({
    required this.allowed,
    required this.reason,
    required this.waitlistStatus,
    required this.eligiblePasses,
  });

  factory BookingOptions.fromJson(Map<String, dynamic> json) {
    return BookingOptions(
      allowed: json['allowed'] as bool,
      reason: json['reason'] as String?,
      waitlistStatus: json['waitlistStatus'] as String?,
      eligiblePasses: [
        for (final pass in json['eligiblePasses'] as List)
          EligiblePass.fromJson(pass as Map<String, dynamic>),
      ],
    );
  }

  /// Whether a place can be booked now.
  final bool allowed;

  /// The first rule the booking breaks, e.g. `CLASS_FULL`,
  /// `BOOKING_CLOSED` or `NO_ELIGIBLE_PASS`; `null` when [allowed].
  final String? reason;

  /// `WAITING` or `OFFERED` when the participant is on the waiting list.
  final String? waitlistStatus;

  /// The participant's passes that can fund the class; one of them is
  /// charged.
  final List<EligiblePass> eligiblePasses;
}

/// A rejected booking with the backend's stable reason code; server text is
/// never shown in the UI.
class ClassBookingException extends ApiException {
  const ClassBookingException({super.statusCode, this.code})
    : super(message: 'Class booking request failed');

  /// E.g. `CLASS_FULL`, or `null` without a response.
  final String? code;
}

/// Bookings the signed-in trainer makes for participants on the group
/// classes they run or assist, on the rules of the participant's own booking
/// (`OwnEventReservationController` in the backend).
class ClassBookingApi {
  const ClassBookingApi(this._dio);

  final Dio _dio;

  Future<BookingOptions> fetchOptions(String eventId, String customerId) =>
      _request(() async {
        final response = await _dio.get<Map<String, dynamic>>(
          '$ownScheduleEventsPath/$eventId/booking-options',
          queryParameters: {'customerId': customerId},
        );
        return BookingOptions.fromJson(response.data!);
      });

  /// Books a place for the participant, charging [passId], one of their
  /// eligible passes.
  Future<void> book(
    String eventId, {
    required String customerId,
    required String passId,
    String? reason,
  }) => _request(() async {
    final trimmed = reason?.trim();
    await _dio.post<Map<String, dynamic>>(
      '$ownScheduleEventsPath/$eventId/reservations',
      data: {
        'customerId': customerId,
        'customerPassId': passId,
        'reason': trimmed == null || trimmed.isEmpty ? null : trimmed,
      },
    );
  });

  Future<T> _request<T>(Future<T> Function() send) async {
    try {
      return await send();
    } on DioException catch (e) {
      final body = e.response?.data;
      throw ClassBookingException(
        statusCode: e.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

final classBookingApiProvider = Provider<ClassBookingApi>(
  (ref) => ClassBookingApi(ref.watch(dioProvider)),
);

/// Account is part of the cache key, so one trainer's answer is never shown
/// to another account.
typedef BookingOptionsQuery = ({
  String accountId,
  String eventId,
  String customerId,
});

final bookingOptionsProvider = FutureProvider.autoDispose
    .family<BookingOptions, BookingOptionsQuery>(
      (ref, query) => ref
          .watch(classBookingApiProvider)
          .fetchOptions(query.eventId, query.customerId),
    );
