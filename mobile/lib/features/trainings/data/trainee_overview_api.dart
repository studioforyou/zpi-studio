import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';

typedef TrainerOverviewScope = ({String accountId, String staffId});
typedef TraineeOverviewQuery = ({
  TrainerOverviewScope scope,
  String customerId,
});
typedef TraineeCalendarQuery = ({
  TrainerOverviewScope scope,
  String customerId,
  ScheduleWindow window,
});
typedef PersonalTrainingsQuery = ({
  TrainerOverviewScope scope,
  ScheduleWindow window,
});

TrainerOverviewScope? trainerOverviewScope(AuthState auth) {
  if (auth case Authenticated(:final session, choosingRole: false)
      when session.activeRole == AppRole.trainer &&
          session.staffProfileId != null) {
    return (accountId: session.accountId, staffId: session.staffProfileId!);
  }
  return null;
}

class TraineePass {
  const TraineePass({
    required this.id,
    required this.productName,
    required this.status,
    required this.entriesRemaining,
    required this.validFrom,
    required this.validUntil,
    required this.serviceTypeIds,
    required this.eligible,
    required this.lowBalance,
  });

  factory TraineePass.fromJson(Map<String, dynamic> json) => TraineePass(
    id: json['id'] as String,
    productName: json['productName'] as String,
    status: json['status'] as String,
    entriesRemaining: json['entriesRemaining'] as int?,
    validFrom: _date(json['validFrom']),
    validUntil: _date(json['validUntil']),
    serviceTypeIds: (json['serviceTypeIds'] as List).cast<String>(),
    eligible: json['eligible'] as bool,
    lowBalance: json['lowBalance'] as bool,
  );

  final String id;
  final String productName;
  final String status;
  final int? entriesRemaining;
  final DateTime? validFrom;
  final DateTime? validUntil;
  final List<String> serviceTypeIds;
  final bool eligible;
  final bool lowBalance;
}

/// A dated personal session, with its own confirmations and entry accounting.
/// Extending ScheduleEvent lets the existing calendar render the same session.
class TraineeTraining extends ScheduleEvent {
  TraineeTraining({
    required ScheduleEvent event,
    required this.reservationId,
    required this.trainerConfirmedAt,
    required this.clientConfirmedAt,
    required this.attendance,
    required this.settlementStatus,
    required this.funding,
    required this.passId,
    required this.passName,
    required this.requiresAttention,
    this.customerId,
    this.customerName,
  }) : eventId = event.id,
       super(
         id: reservationId,
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
       );

  factory TraineeTraining.fromJson(Map<String, dynamic> json) =>
      TraineeTraining(
        event: ScheduleEvent.fromJson(json),
        reservationId: json['reservationId'] as String,
        trainerConfirmedAt: _date(json['trainerConfirmedAt'])?.toLocal(),
        clientConfirmedAt: _date(json['clientConfirmedAt'])?.toLocal(),
        attendance: json['attendance'] as String,
        settlementStatus: json['settlementStatus'] as String,
        funding: json['funding'] as String,
        passId: json['passId'] as String?,
        passName: json['passName'] as String?,
        requiresAttention: json['requiresAttention'] as bool,
        customerId: json['customerId'] as String?,
        customerName: json['customerName'] as String?,
      );

  /// The original schedule event. The calendar row is keyed by reservation,
  /// since one event can contain more than one of the trainer's participants.
  final String eventId;
  final String reservationId;
  final String? customerId;
  final String? customerName;
  final DateTime? trainerConfirmedAt;
  final DateTime? clientConfirmedAt;
  final String attendance;
  final String settlementStatus;
  final String funding;
  final String? passId;
  final String? passName;
  final bool requiresAttention;
}

class TraineeOverview {
  const TraineeOverview({
    required this.customerId,
    required this.displayName,
    required this.isActive,
    required this.availability,
    required this.primaryPassId,
    required this.passes,
    required this.nextTraining,
    required this.attentionTraining,
  });

  factory TraineeOverview.fromJson(Map<String, dynamic> json) =>
      TraineeOverview(
        customerId: json['customerId'] as String,
        displayName: json['displayName'] as String,
        isActive: json['isActive'] as bool,
        availability: json['availability'] as String,
        primaryPassId: json['primaryPassId'] as String?,
        passes: [
          for (final pass in json['passes'] as List)
            TraineePass.fromJson(pass as Map<String, dynamic>),
        ],
        nextTraining: _training(json['nextTraining']),
        attentionTraining: _training(json['attentionTraining']),
      );

  final String customerId;
  final String displayName;
  final bool isActive;
  final String availability;
  final String? primaryPassId;
  final List<TraineePass> passes;
  final TraineeTraining? nextTraining;
  final TraineeTraining? attentionTraining;

  TraineePass? get primaryPass =>
      passes.where((pass) => pass.id == primaryPassId).firstOrNull;
}

class TraineeOverviewList {
  const TraineeOverviewList({
    required this.checkedOn,
    required this.checkedAt,
    required this.items,
  });

  factory TraineeOverviewList.fromJson(Map<String, dynamic> json) =>
      TraineeOverviewList(
        checkedOn: DateTime.parse(json['checkedOn'] as String),
        checkedAt: DateTime.parse(json['checkedAt'] as String),
        items: [
          for (final item in json['items'] as List)
            TraineeOverview.fromJson(item as Map<String, dynamic>),
        ],
      );

  final DateTime checkedOn;
  final DateTime checkedAt;
  final List<TraineeOverview> items;
}

class TraineeOverviewDetails {
  const TraineeOverviewDetails({
    required this.checkedOn,
    required this.checkedAt,
    required this.trainee,
  });

  factory TraineeOverviewDetails.fromJson(Map<String, dynamic> json) =>
      TraineeOverviewDetails(
        checkedOn: DateTime.parse(json['checkedOn'] as String),
        checkedAt: DateTime.parse(json['checkedAt'] as String),
        trainee: TraineeOverview.fromJson(json),
      );

  final DateTime checkedOn;
  final DateTime checkedAt;
  final TraineeOverview trainee;
}

class PersonalTrainingsSnapshot {
  const PersonalTrainingsSnapshot({
    required this.checkedOn,
    required this.checkedAt,
    required this.items,
  });

  factory PersonalTrainingsSnapshot.fromJson(Map<String, dynamic> json) =>
      PersonalTrainingsSnapshot(
        checkedOn: DateTime.parse(json['checkedOn'] as String),
        checkedAt: DateTime.parse(json['checkedAt'] as String),
        items: [
          for (final item in json['items'] as List)
            TraineeTraining.fromJson(item as Map<String, dynamic>),
        ],
      );

  final DateTime checkedOn;
  final DateTime checkedAt;
  final List<TraineeTraining> items;
}

DateTime? _date(dynamic value) =>
    value == null ? null : DateTime.parse(value as String);
TraineeTraining? _training(dynamic value) => value == null
    ? null
    : TraineeTraining.fromJson(value as Map<String, dynamic>);

class TraineeOverviewApi {
  const TraineeOverviewApi(this._dio);
  final Dio _dio;

  Future<TraineeOverviewList> fetchList() async =>
      TraineeOverviewList.fromJson(await _get('/api/v1/me/trainees/overview'));
  Future<TraineeOverviewDetails> fetchDetails(String customerId) async =>
      TraineeOverviewDetails.fromJson(
        await _get('/api/v1/me/trainees/$customerId/overview'),
      );
  Future<List<TraineeTraining>> fetchCalendar(
    String customerId,
    ScheduleWindow window,
  ) async {
    final json =
        await _get('/api/v1/me/trainees/$customerId/personal-trainings', {
          'from': window.from.toUtc().toIso8601String(),
          'to': window.to.toUtc().toIso8601String(),
        });
    return [
      for (final item in json['items'] as List)
        TraineeTraining.fromJson(item as Map<String, dynamic>),
    ];
  }

  Future<PersonalTrainingsSnapshot> fetchOwnCalendar(
    ScheduleWindow window,
  ) async => PersonalTrainingsSnapshot.fromJson(
    await _get('/api/v1/me/trainees/personal-trainings', {
      'from': window.from.toUtc().toIso8601String(),
      'to': window.to.toUtc().toIso8601String(),
    }),
  );

  Future<Map<String, dynamic>> _get(
    String path, [
    Map<String, dynamic>? query,
  ]) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        path,
        queryParameters: query,
      );
      return response.data!;
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final traineeOverviewApiProvider = Provider<TraineeOverviewApi>(
  (ref) => TraineeOverviewApi(ref.watch(dioProvider)),
);

void _requireScope(Ref ref, TrainerOverviewScope scope) {
  if (trainerOverviewScope(ref.watch(authControllerProvider)) != scope) {
    throw const ApiException(
      statusCode: 403,
      message: 'Trainer scope changed.',
    );
  }
}

final traineeOverviewsProvider = FutureProvider.autoDispose
    .family<TraineeOverviewList, TrainerOverviewScope>((ref, scope) {
      _requireScope(ref, scope);
      return ref.watch(traineeOverviewApiProvider).fetchList();
    });

final traineeOverviewProvider = FutureProvider.autoDispose
    .family<TraineeOverviewDetails, TraineeOverviewQuery>((ref, query) {
      _requireScope(ref, query.scope);
      return ref
          .watch(traineeOverviewApiProvider)
          .fetchDetails(query.customerId);
    });

final traineeCalendarProvider = FutureProvider.autoDispose
    .family<List<ScheduleEvent>, TraineeCalendarQuery>((ref, query) {
      _requireScope(ref, query.scope);
      return ref
          .watch(traineeOverviewApiProvider)
          .fetchCalendar(query.customerId, query.window);
    });

final ownPersonalTrainingsProvider = FutureProvider.autoDispose
    .family<PersonalTrainingsSnapshot, PersonalTrainingsQuery>((ref, query) {
      _requireScope(ref, query.scope);
      return ref
          .watch(traineeOverviewApiProvider)
          .fetchOwnCalendar(query.window);
    });

/// The main schedule is authoritative for which events belong to the trainer.
/// Enrich its personal sessions with reservation states, retaining unbooked
/// sessions and sessions without a current trainee assignment as ordinary events.
final trainerScheduleEventsProvider = FutureProvider.autoDispose
    .family<List<ScheduleEvent>, PersonalTrainingsQuery>((ref, query) async {
      _requireScope(ref, query.scope);
      final events = await ref.watch(
        ownScheduleProvider((
          accountId: query.scope.accountId,
          window: query.window,
        )).future,
      );
      // Trainers with only group classes do not need the personal-session read.
      if (!ref.mounted ||
          !events.any((event) => event.kind == EventKind.personalTraining)) {
        return events;
      }
      final snapshot = await ref.watch(
        ownPersonalTrainingsProvider(query).future,
      );
      final byEvent = <String, List<TraineeTraining>>{};
      for (final training in snapshot.items) {
        byEvent.putIfAbsent(training.eventId, () => []).add(training);
      }
      return [
        for (final event in events)
          if (event.kind == EventKind.personalTraining &&
              byEvent.containsKey(event.id))
            ...byEvent[event.id]!
          else
            event,
      ];
    });
