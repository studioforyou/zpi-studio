import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Keep responses separate across both logins and participant profiles.
typedef MakeupScope = ({String accountId, String profileId});
typedef MakeupQuery = ({MakeupScope scope, int page});

class MakeupCredit {
  const MakeupCredit({
    required this.id,
    required this.productName,
    required this.expiresAt,
  });

  factory MakeupCredit.fromJson(Map<String, dynamic> json) => MakeupCredit(
    id: json['id'] as String,
    productName: json['productName'] as String,
    expiresAt: DateTime.parse(json['expiresAt'] as String).toLocal(),
  );

  final String id;
  final String productName;
  final DateTime expiresAt;
}

class MakeupCreditPage {
  const MakeupCreditPage({
    required this.items,
    required this.page,
    required this.totalItems,
    required this.totalPages,
  });

  factory MakeupCreditPage.fromJson(Map<String, dynamic> json) =>
      MakeupCreditPage(
        items: [
          for (final item in json['items'] as List)
            MakeupCredit.fromJson(item as Map<String, dynamic>),
        ],
        page: json['page'] as int,
        totalItems: json['totalItems'] as int,
        totalPages: json['totalPages'] as int,
      );

  final List<MakeupCredit> items;
  final int page;
  final int totalItems;
  final int totalPages;
}

class MakeupCreditsApi {
  const MakeupCreditsApi(this._dio);

  final Dio _dio;

  Future<MakeupCreditPage> fetch(String profileId, {int page = 0}) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/profiles/$profileId/makeup-credits',
        queryParameters: {'page': page, 'size': 20},
      );
      return MakeupCreditPage.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final makeupCreditsApiProvider = Provider<MakeupCreditsApi>(
  (ref) => MakeupCreditsApi(ref.watch(dioProvider)),
);

final makeupCreditsProvider = FutureProvider.autoDispose
    .family<MakeupCreditPage, MakeupQuery>(
      (ref, query) => ref
          .watch(makeupCreditsApiProvider)
          .fetch(query.scope.profileId, page: query.page),
    );
