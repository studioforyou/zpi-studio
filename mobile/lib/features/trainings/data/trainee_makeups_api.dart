import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';

/// Why a make-up credit was granted.
enum MakeupReason { cancelledByStudio, cancelledInTime, disputeUpheld, other }

MakeupReason _reason(String? value) => switch (value) {
  'CANCELLED_BY_STUDIO' => MakeupReason.cancelledByStudio,
  'CANCELLED_IN_TIME' => MakeupReason.cancelledInTime,
  'DISPUTE_UPHELD' => MakeupReason.disputeUpheld,
  _ => MakeupReason.other,
};

/// The cancelled class a credit was granted for.
class MakeupOrigin {
  const MakeupOrigin({
    required this.title,
    required this.startsAt,
    required this.reason,
  });

  factory MakeupOrigin.fromJson(Map<String, dynamic> json) => MakeupOrigin(
    title: json['title'] as String?,
    startsAt: DateTime.parse(json['startsAt'] as String).toLocal(),
    reason: _reason(json['reason'] as String?),
  );

  final String? title;
  final DateTime startsAt;
  final MakeupReason reason;
}

class MakeupService {
  const MakeupService({required this.id, required this.name});

  factory MakeupService.fromJson(Map<String, dynamic> json) =>
      MakeupService(id: json['id'] as String, name: json['name'] as String);

  final String id;
  final String name;
}

/// An unused credit that has not expired; valid strictly before [expiresAt].
class MakeupCredit {
  const MakeupCredit({
    required this.id,
    required this.sourcePassId,
    required this.productName,
    required this.policyName,
    required this.grantedAt,
    required this.expiresAt,
    required this.origin,
    required this.services,
  });

  factory MakeupCredit.fromJson(Map<String, dynamic> json) => MakeupCredit(
    id: json['id'] as String,
    sourcePassId: json['sourcePassId'] as String,
    productName: json['productName'] as String,
    policyName: json['policyName'] as String?,
    grantedAt: DateTime.parse(json['grantedAt'] as String).toLocal(),
    expiresAt: DateTime.parse(json['expiresAt'] as String).toLocal(),
    origin: json['origin'] == null
        ? null
        : MakeupOrigin.fromJson(json['origin'] as Map<String, dynamic>),
    services: [
      for (final service in json['services'] as List)
        MakeupService.fromJson(service as Map<String, dynamic>),
    ],
  );

  final String id;
  final String sourcePassId;
  final String productName;
  final String? policyName;
  final DateTime grantedAt;
  final DateTime expiresAt;

  /// `null` when the studio granted the credit directly.
  final MakeupOrigin? origin;
  final List<MakeupService> services;
}

/// How many credits a pass has given out of the most its policy allows.
class MakeupLimit {
  const MakeupLimit({
    required this.passId,
    required this.productName,
    required this.granted,
    required this.max,
    required this.reached,
  });

  factory MakeupLimit.fromJson(Map<String, dynamic> json) => MakeupLimit(
    passId: json['passId'] as String,
    productName: json['productName'] as String,
    granted: json['granted'] as int,
    max: json['max'] as int,
    reached: json['reached'] as bool,
  );

  final String passId;
  final String productName;
  final int granted;
  final int max;
  final bool reached;
}

class TraineeMakeups {
  const TraineeMakeups({
    required this.checkedOn,
    required this.totalCount,
    required this.nearestExpiresAt,
    required this.items,
    required this.limits,
  });

  factory TraineeMakeups.fromJson(Map<String, dynamic> json) => TraineeMakeups(
    checkedOn: DateTime.parse(json['checkedOn'] as String),
    totalCount: json['totalCount'] as int,
    nearestExpiresAt: json['nearestExpiresAt'] == null
        ? null
        : DateTime.parse(json['nearestExpiresAt'] as String).toLocal(),
    items: [
      for (final item in json['items'] as List)
        MakeupCredit.fromJson(item as Map<String, dynamic>),
    ],
    limits: [
      for (final limit in json['limits'] as List)
        MakeupLimit.fromJson(limit as Map<String, dynamic>),
    ],
  );

  /// The studio's date the snapshot was taken on.
  final DateTime checkedOn;
  final int totalCount;
  final DateTime? nearestExpiresAt;
  final List<MakeupCredit> items;
  final List<MakeupLimit> limits;

  MakeupLimit? limitFor(String passId) =>
      limits.where((limit) => limit.passId == passId).firstOrNull;
}

/// Read-only: the trainer sees the credits but never books or spends them.
class TraineeMakeupsApi {
  const TraineeMakeupsApi(this._dio);
  final Dio _dio;

  Future<TraineeMakeups> fetch(String customerId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/trainees/$customerId/makeup-credits',
      );
      return TraineeMakeups.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final traineeMakeupsApiProvider = Provider<TraineeMakeupsApi>(
  (ref) => TraineeMakeupsApi(ref.watch(dioProvider)),
);

final traineeMakeupsProvider = FutureProvider.autoDispose
    .family<TraineeMakeups, TraineeOverviewQuery>((ref, query) {
      if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
          query.scope) {
        throw const ApiException(
          statusCode: 403,
          message: 'Trainer scope changed.',
        );
      }
      return ref.watch(traineeMakeupsApiProvider).fetch(query.customerId);
    });
