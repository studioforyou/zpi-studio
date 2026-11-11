import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

typedef MakeupBookingQuery = ({
  MakeupScope scope,
  String creditId,
  DateTime month,
});

MakeupScope? makeupBookingScope(AuthState auth) => switch (auth) {
  Authenticated(:final session, choosingRole: false)
      when session.activeRole == AppRole.client &&
          session.activeProfileId != null =>
    (accountId: session.accountId, profileId: session.activeProfileId!),
  _ => null,
};

class MakeupOffer {
  const MakeupOffer({
    required this.productName,
    required this.expiresAt,
    required this.items,
  });
  factory MakeupOffer.fromJson(Map<String, dynamic> json) => MakeupOffer(
    productName: json['productName'] as String,
    expiresAt: DateTime.parse(json['expiresAt'] as String).toLocal(),
    items: [
      for (final item in json['items'] as List)
        GroupClass.fromJson(item as Map<String, dynamic>),
    ],
  );
  final String productName;
  final DateTime expiresAt;
  final List<GroupClass> items;
}

class MakeupBookingApi {
  const MakeupBookingApi(this._dio);
  final Dio _dio;
  String _path(MakeupBookingQuery query) =>
      '/api/v1/me/profiles/${query.scope.profileId}/makeup-credits/${query.creditId}/group-classes';

  Future<MakeupOffer> fetch(MakeupBookingQuery query) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        _path(query),
        queryParameters: {
          'from': query.month.toUtc().toIso8601String(),
          'to': DateTime(
            query.month.year,
            query.month.month + 1,
          ).toUtc().toIso8601String(),
        },
      );
      return MakeupOffer.fromJson(response.data!);
    } on DioException catch (error) {
      throw _failure(error);
    }
  }

  Future<ClassReservation> reserve(
    MakeupBookingQuery query,
    String eventId,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '${_path(query)}/$eventId/reservations',
      );
      return ClassReservation.fromJson(response.data!);
    } on DioException catch (error) {
      throw _failure(error);
    }
  }

  ClassBookingException _failure(DioException error) {
    final body = error.response?.data;
    return ClassBookingException(
      statusCode: error.response?.statusCode,
      code: body is Map ? body['code'] as String? : null,
    );
  }
}

final makeupBookingApiProvider = Provider<MakeupBookingApi>(
  (ref) => MakeupBookingApi(ref.watch(dioProvider)),
);
final makeupBookingProvider = FutureProvider.autoDispose
    .family<MakeupOffer, MakeupBookingQuery>((ref, query) {
      if (makeupBookingScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const ClassBookingException(statusCode: 403);
      }
      return ref.watch(makeupBookingApiProvider).fetch(query);
    });
