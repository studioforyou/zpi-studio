import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

typedef PassUsageQuery = ({
  String accountId,
  String? profileId,
  String? productId,
  String? passId,
  String? from,
  String? to,
  bool includeUpcoming,
  int page,
});

String? passUsageAccount(AuthState auth) {
  if (auth case Authenticated(
    :final session,
    choosingRole: false,
  ) when session.activeRole == AppRole.client) {
    return session.accountId;
  }
  return null;
}

class PassUsageEntry {
  const PassUsageEntry({
    required this.id,
    required this.reservationId,
    required this.kind,
    required this.entriesChange,
    required this.occurredAt,
    required this.title,
    required this.startsAt,
    required this.endsAt,
    required this.cancelled,
    required this.profileId,
    required this.profileName,
    required this.passId,
    required this.passName,
  });

  factory PassUsageEntry.fromJson(Map<String, dynamic> json) => PassUsageEntry(
    id: json['id'] as String,
    reservationId: json['reservationId'] as String?,
    kind: json['kind'] as String,
    entriesChange: json['entriesChange'] as int?,
    occurredAt: DateTime.parse(json['occurredAt'] as String).toLocal(),
    title: json['title'] as String? ?? json['serviceTypeName'] as String?,
    startsAt: _date(json['startsAt']),
    endsAt: _date(json['endsAt']),
    cancelled: json['cancelled'] as bool,
    profileId: json['profileId'] as String,
    profileName: json['profileName'] as String,
    passId: json['passId'] as String,
    passName: json['passName'] as String,
  );

  final String id;
  final String? reservationId;
  final String kind;
  final int? entriesChange;
  final DateTime occurredAt;
  final String? title;
  final DateTime? startsAt;
  final DateTime? endsAt;
  final bool cancelled;
  final String profileId;
  final String profileName;
  final String passId;
  final String passName;

  static DateTime? _date(dynamic value) =>
      value == null ? null : DateTime.parse(value as String).toLocal();
}

class PassUsageOption {
  const PassUsageOption({required this.id, required this.name});
  factory PassUsageOption.fromJson(Map<String, dynamic> json) =>
      PassUsageOption(id: json['id'] as String, name: json['name'] as String);
  final String id;
  final String name;
}

class PassUsagePage {
  const PassUsagePage({
    required this.items,
    required this.page,
    required this.totalItems,
    required this.totalPages,
    this.participants = const [],
    this.products = const [],
    this.selectedPassName,
  });
  factory PassUsagePage.fromJson(Map<String, dynamic> json) {
    final entries = json['entries'] as Map<String, dynamic>;
    return PassUsagePage(
      items: [
        for (final item in entries['items'] as List)
          PassUsageEntry.fromJson(item as Map<String, dynamic>),
      ],
      page: entries['page'] as int,
      totalItems: entries['totalItems'] as int,
      totalPages: entries['totalPages'] as int,
      participants: [
        for (final item in json['participants'] as List)
          PassUsageOption.fromJson(item as Map<String, dynamic>),
      ],
      products: [
        for (final item in json['products'] as List)
          PassUsageOption.fromJson(item as Map<String, dynamic>),
      ],
      selectedPassName: json['selectedPassName'] as String?,
    );
  }
  final List<PassUsageEntry> items;
  final int page;
  final int totalItems;
  final int totalPages;
  final List<PassUsageOption> participants;
  final List<PassUsageOption> products;
  final String? selectedPassName;
}

class PassUsageApi {
  const PassUsageApi(this._dio);
  final Dio _dio;

  Future<PassUsagePage> fetch(PassUsageQuery query) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/pass-entry-history',
        queryParameters: {
          'page': query.page,
          'size': 20,
          'includeUpcoming': query.includeUpcoming,
          if (query.profileId != null) 'profileId': query.profileId,
          if (query.productId != null) 'productId': query.productId,
          if (query.passId != null) 'passId': query.passId,
          if (query.from != null) 'from': query.from,
          if (query.to != null) 'to': query.to,
        },
      );
      return PassUsagePage.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final passUsageApiProvider = Provider<PassUsageApi>(
  (ref) => PassUsageApi(ref.watch(dioProvider)),
);
final passUsageProvider = FutureProvider.autoDispose
    .family<PassUsagePage, PassUsageQuery>((ref, query) {
      if (passUsageAccount(ref.watch(authControllerProvider)) !=
          query.accountId) {
        throw const ApiException(
          statusCode: 403,
          message: 'Account scope changed.',
        );
      }
      return ref.watch(passUsageApiProvider).fetch(query);
    });
