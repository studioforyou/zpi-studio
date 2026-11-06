import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The inbox of one login in one role: notifications of an account with
/// several roles are marked with the role they concern.
typedef InboxScope = ({String accountId, String role});
typedef InboxQuery = ({InboxScope scope, int page});

/// A notification in the in-app inbox.
class AppNotification {
  const AppNotification({
    required this.id,
    required this.type,
    required this.title,
    required this.body,
    required this.data,
    required this.createdAt,
    this.role,
    this.subjectProfileId,
    this.readAt,
  });

  factory AppNotification.fromJson(Map<String, dynamic> json) =>
      AppNotification(
        id: json['id'] as String,
        type: json['type'] as String,
        role: json['role'] as String?,
        subjectProfileId: json['subjectProfileId'] as String?,
        title: json['title'] as String,
        body: json['body'] as String,
        data: {
          for (final entry
              in ((json['data'] as Map<String, dynamic>?) ?? const {}).entries)
            entry.key: entry.value.toString(),
        },
        createdAt: DateTime.parse(json['createdAt'] as String).toLocal(),
        readAt: switch (json['readAt']) {
          final String value => DateTime.parse(value).toLocal(),
          _ => null,
        },
      );

  final String id;

  /// e.g. `CLASS_REMINDER`.
  final String type;

  /// `CLIENT` or `TRAINER`; `null` for older notifications.
  final String? role;

  /// The child the notification is about, when it is not the account's own.
  final String? subjectProfileId;
  final String title;
  final String body;

  /// What opening it needs, e.g. `eventId`, `profileId`.
  final Map<String, String> data;
  final DateTime createdAt;
  final DateTime? readAt;

  bool get isUnread => readAt == null;
}

class NotificationPage {
  const NotificationPage({
    required this.items,
    required this.page,
    required this.totalItems,
    required this.totalPages,
  });

  factory NotificationPage.fromJson(Map<String, dynamic> json) =>
      NotificationPage(
        items: [
          for (final item in json['items'] as List)
            AppNotification.fromJson(item as Map<String, dynamic>),
        ],
        page: json['page'] as int,
        totalItems: json['totalItems'] as int,
        totalPages: json['totalPages'] as int,
      );

  final List<AppNotification> items;
  final int page;
  final int totalItems;
  final int totalPages;
}

class NotificationsApi {
  const NotificationsApi(this._dio);

  final Dio _dio;

  static const pageSize = 20;

  Future<NotificationPage> fetch(String role, {int page = 0}) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/notifications',
        queryParameters: {'role': role, 'page': page, 'size': pageSize},
      );
      return NotificationPage.fromJson(response.data!);
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  Future<int> unreadCount(String role) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/v1/me/notifications/unread-count',
        queryParameters: {'role': role},
      );
      return response.data!['count'] as int;
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  Future<void> markRead(String notificationId) async {
    try {
      await _dio.post<void>('/api/v1/me/notifications/$notificationId/read');
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  Future<void> markAllRead(String role) async {
    try {
      await _dio.post<void>(
        '/api/v1/me/notifications/read-all',
        queryParameters: {'role': role},
      );
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}

final notificationsApiProvider = Provider<NotificationsApi>(
  (ref) => NotificationsApi(ref.watch(dioProvider)),
);

final inboxProvider = FutureProvider.autoDispose
    .family<NotificationPage, InboxQuery>(
      (ref, query) => ref
          .watch(notificationsApiProvider)
          .fetch(query.scope.role, page: query.page),
    );

final unreadCountProvider = FutureProvider.autoDispose.family<int, InboxScope>(
  (ref, scope) => ref.watch(notificationsApiProvider).unreadCount(scope.role),
);
