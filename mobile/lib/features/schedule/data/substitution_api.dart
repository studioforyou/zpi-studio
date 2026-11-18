import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';

class SubstitutionRequest {
  const SubstitutionRequest({
    required this.id,
    required this.eventId,
    required this.status,
    required this.reason,
    required this.createdAt,
    required this.canCancel,
  });

  factory SubstitutionRequest.fromJson(Map<String, dynamic> json) =>
      SubstitutionRequest(
        id: json['id'] as String,
        eventId: json['eventId'] as String,
        status: json['status'] as String,
        reason: json['reason'] as String?,
        createdAt: DateTime.parse(json['createdAt'] as String).toLocal(),
        canCancel: json['canCancel'] as bool,
      );

  final String id;
  final String eventId;
  final String status;
  final String? reason;
  final DateTime createdAt;
  final bool canCancel;
}

class SubstitutionState {
  const SubstitutionState({required this.canRequest, this.request});

  factory SubstitutionState.fromJson(Map<String, dynamic> json) =>
      SubstitutionState(
        canRequest: json['canRequest'] as bool,
        request: json['request'] == null
            ? null
            : SubstitutionRequest.fromJson(
                json['request'] as Map<String, dynamic>,
              ),
      );

  final bool canRequest;
  final SubstitutionRequest? request;
}

class SubstitutionException extends ApiException {
  const SubstitutionException({
    super.statusCode,
    required super.message,
    this.code,
  });
  final String? code;
}

class SubstitutionApi {
  const SubstitutionApi(this._dio);
  final Dio _dio;
  static const path = '/api/v1/me/substitution-requests';
  static String _event(String id) =>
      '/api/v1/me/schedule-events/$id/substitution-request';

  Future<List<SubstitutionRequest>> fetchOwn(ScheduleWindow window) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        path,
        queryParameters: {
          'from': window.from.toUtc().toIso8601String(),
          'to': window.to.toUtc().toIso8601String(),
        },
      );
      return [
        for (final item in response.data!['items'] as List)
          SubstitutionRequest.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw _error(e);
    }
  }

  Future<SubstitutionState> fetchState(String eventId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(_event(eventId));
      return SubstitutionState.fromJson(response.data!);
    } on DioException catch (e) {
      throw _error(e);
    }
  }

  Future<void> create(String eventId, String reason) async {
    try {
      await _dio.post<void>(
        _event(eventId),
        data: {'reason': reason.trim().isEmpty ? null : reason.trim()},
      );
    } on DioException catch (e) {
      throw _error(e);
    }
  }

  Future<void> cancel(String requestId) async {
    try {
      await _dio.post<void>('$path/$requestId/cancel');
    } on DioException catch (e) {
      throw _error(e);
    }
  }

  static ApiException _error(DioException e) {
    final body = e.response?.data;
    return SubstitutionException(
      statusCode: e.response?.statusCode,
      message: 'Substitution request failed',
      code: body is Map ? body['code'] as String? : null,
    );
  }
}

final substitutionApiProvider = Provider<SubstitutionApi>(
  (ref) => SubstitutionApi(ref.watch(dioProvider)),
);

typedef SubstitutionQuery = ({TrainerOverviewScope scope, String eventId});

final ownSubstitutionStateProvider = FutureProvider.autoDispose
    .family<SubstitutionState, SubstitutionQuery>((ref, query) {
      _requireScope(ref, query.scope);
      return ref.watch(substitutionApiProvider).fetchState(query.eventId);
    });

final ownSubstitutionsProvider = FutureProvider.autoDispose
    .family<List<SubstitutionRequest>, PersonalTrainingsQuery>((ref, query) {
      _requireScope(ref, query.scope);
      return ref.watch(substitutionApiProvider).fetchOwn(query.window);
    });

void _requireScope(Ref ref, TrainerOverviewScope scope) {
  if (trainerOverviewScope(ref.watch(authControllerProvider)) != scope) {
    throw const ApiException(
      statusCode: 403,
      message: 'Trainer scope changed.',
    );
  }
}

String substitutionEventId(ScheduleEvent event) =>
    event is TraineeTraining ? event.eventId : event.id;
