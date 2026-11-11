import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';

typedef PersonalSessionQuery = ({BookingScope scope, String reservationId});

BookingScope? personalTrainingScope(AuthState auth) => switch (auth) {
  Authenticated(choosingRole: false) => bookingScope(auth),
  _ => null,
};

class PersonalCancellation {
  const PersonalCancellation({
    required this.allowed,
    this.reason,
    this.freeCancelUntil,
    required this.late,
    this.effect,
    this.validityDays,
    this.consequenceReason,
  });
  factory PersonalCancellation.fromJson(Map<String, dynamic> json) =>
      PersonalCancellation(
        allowed: json['allowed'] as bool,
        reason: json['reason'] as String?,
        freeCancelUntil: _date(json['freeCancelUntil']),
        late: json['late'] as bool,
        effect: json['effect'] as String?,
        validityDays: json['validityDays'] as int?,
        consequenceReason: json['consequenceReason'] as String?,
      );
  final bool allowed;
  final String? reason;
  final DateTime? freeCancelUntil;
  final bool late;
  final String? effect;
  final int? validityDays;
  final String? consequenceReason;
}

class PersonalDispute {
  const PersonalDispute({
    required this.status,
    required this.reason,
    this.resolutionNote,
  });
  factory PersonalDispute.fromJson(Map<String, dynamic> json) =>
      PersonalDispute(
        status: json['status'] as String,
        reason: json['reason'] as String,
        resolutionNote: json['resolutionNote'] as String?,
      );
  final String status;
  final String reason;
  final String? resolutionNote;
}

class PersonalSession extends ScheduleEvent {
  const PersonalSession({
    required super.id,
    required this.reservationId,
    required super.status,
    required super.title,
    required super.serviceTypeName,
    super.serviceTypeId,
    required super.startsAt,
    required super.endsAt,
    required super.staff,
    required super.roomNames,
    required this.version,
    required this.cancellation,
    required this.canDispute,
    this.disputeReason,
    this.disputeClosesAt,
    this.dispute,
    this.passName,
    required this.attendance,
    this.trainerConfirmedAt,
    required this.settlementStatus,
    this.cancellationEffect,
  }) : super(kind: EventKind.personalTraining, capacity: 1);
  factory PersonalSession.fromJson(
    Map<String, dynamic> json,
  ) => PersonalSession(
    id: json['id'] as String,
    reservationId: json['reservationId'] as String,
    status: EventStatus.fromCode(json['status'] as String),
    title: json['title'] as String?,
    serviceTypeName: json['serviceTypeName'] as String?,
    serviceTypeId: json['serviceTypeId'] as String?,
    startsAt: _date(json['startsAt'])!,
    endsAt: _date(json['endsAt'])!,
    staff: [
      for (final person in json['staff'] as List)
        EventStaff.fromJson(person as Map<String, dynamic>),
    ],
    roomNames: [
      for (final room in json['resources'] as List)
        (room as Map<String, dynamic>)['name'] as String,
    ],
    version: json['version'] as int,
    passName: json['passName'] as String?,
    attendance: json['attendance'] as String,
    trainerConfirmedAt: _date(json['trainerConfirmedAt']),
    settlementStatus: json['settlementStatus'] as String,
    cancellationEffect: json['cancellationEffect'] as String?,
    cancellation: PersonalCancellation.fromJson(
      json['cancellation'] as Map<String, dynamic>,
    ),
    canDispute: (json['disputeAvailability'] as Map)['allowed'] as bool,
    disputeReason: (json['disputeAvailability'] as Map)['reason'] as String?,
    disputeClosesAt: _date((json['disputeAvailability'] as Map)['closesAt']),
    dispute: json['dispute'] == null
        ? null
        : PersonalDispute.fromJson(json['dispute'] as Map<String, dynamic>),
  );
  final String reservationId;
  final int version;
  final String? passName;
  final String attendance;
  final DateTime? trainerConfirmedAt;
  final String settlementStatus;
  final String? cancellationEffect;
  final PersonalCancellation cancellation;
  final bool canDispute;
  final String? disputeReason;
  final DateTime? disputeClosesAt;
  final PersonalDispute? dispute;
}

DateTime? _date(dynamic value) =>
    value == null ? null : DateTime.parse(value as String).toLocal();

class PersonalTrainingsApi {
  const PersonalTrainingsApi(this._dio);
  final Dio _dio;
  String _path(BookingScope scope) =>
      '/api/v1/me/profiles/${scope.profileId}/personal-trainings';
  Future<List<PersonalSession>> fetch(ClassesQuery query) async {
    final data = await _request(
      () => _dio.get<Map<String, dynamic>>(
        _path(query.scope),
        queryParameters: {
          'from': query.window.from.toUtc().toIso8601String(),
          'to': query.window.to.toUtc().toIso8601String(),
        },
      ),
    );
    return [
      for (final item in data['items'] as List)
        PersonalSession.fromJson(item as Map<String, dynamic>),
    ];
  }

  Future<PersonalSession> get(PersonalSessionQuery query) async =>
      PersonalSession.fromJson(
        await _request(
          () => _dio.get<Map<String, dynamic>>(
            '${_path(query.scope)}/${query.reservationId}',
          ),
        ),
      );
  Future<PersonalSession> cancel(
    BookingScope scope,
    PersonalSession session,
  ) async => PersonalSession.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_path(scope)}/${session.reservationId}/cancel',
        data: {
          'expectedVersion': session.version,
          'expectedEffect': session.cancellation.effect,
          'expectedValidityDays': session.cancellation.validityDays,
        },
      ),
    ),
  );
  Future<PersonalSession> dispute(
    BookingScope scope,
    PersonalSession session,
    String reason,
  ) async => PersonalSession.fromJson(
    await _request(
      () => _dio.post<Map<String, dynamic>>(
        '${_path(scope)}/${session.reservationId}/disputes',
        data: {'expectedVersion': session.version, 'reason': reason},
      ),
    ),
  );
  Future<Map<String, dynamic>> _request(
    Future<Response<Map<String, dynamic>>> Function() send,
  ) async {
    try {
      return (await send()).data!;
    } on DioException catch (error) {
      final body = error.response?.data;
      throw ClassBookingException(
        statusCode: error.response?.statusCode,
        code: body is Map ? body['code'] as String? : null,
      );
    }
  }
}

final personalTrainingsApiProvider = Provider<PersonalTrainingsApi>(
  (ref) => PersonalTrainingsApi(ref.watch(dioProvider)),
);
final personalTrainingsProvider = FutureProvider.autoDispose
    .family<List<PersonalSession>, ClassesQuery>((ref, query) {
      final auth = ref.watch(authControllerProvider);
      if (auth is! Authenticated ||
          auth.choosingRole ||
          auth.session.activeRole != AppRole.client ||
          auth.session.accountId != query.scope.accountId ||
          !auth.session.profiles.any((p) => p.id == query.scope.profileId)) {
        throw const ClassBookingException(statusCode: 403);
      }
      return ref.watch(personalTrainingsApiProvider).fetch(query);
    });
final personalSessionProvider = FutureProvider.autoDispose
    .family<PersonalSession, PersonalSessionQuery>((ref, query) {
      if (personalTrainingScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const ClassBookingException(statusCode: 403);
      }
      return ref.watch(personalTrainingsApiProvider).get(query);
    });
