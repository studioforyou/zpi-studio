import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The signed-in account's private calendar feed link
/// (`OwnCalendarFeedController` in the backend).
const ownCalendarFeedPath = '/api/v1/me/calendar-feed';

/// Which part of the schedule an external calendar subscribes to. An account
/// that is both a client and a trainer chooses; any other account gets its
/// whole schedule.
enum CalendarFeedScope {
  all(null),
  client('CLIENT'),
  trainer('TRAINER');

  const CalendarFeedScope(this.role);

  /// The feed's `role` query parameter, or `null` for the whole schedule.
  final String? role;
}

/// Whether the account has a working link (`GET /api/v1/me/calendar-feed`).
class CalendarFeedStatus {
  const CalendarFeedStatus({required this.active, required this.createdAt});

  factory CalendarFeedStatus.fromJson(Map<String, dynamic> json) =>
      CalendarFeedStatus(
        active: json['active'] as bool,
        createdAt: json['createdAt'] == null
            ? null
            : DateTime.parse(json['createdAt'] as String),
      );

  final bool active;
  final DateTime? createdAt;
}

/// A link to the feed. The backend returns it only when it is created, so the
/// app keeps it on this device.
class CalendarFeedLink {
  const CalendarFeedLink({
    required this.url,
    required this.webcalUrl,
    required this.createdAt,
  });

  factory CalendarFeedLink.fromJson(Map<String, dynamic> json) =>
      CalendarFeedLink(
        url: json['url'] as String,
        webcalUrl: json['webcalUrl'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );

  /// The https address, for Google Calendar and for copying.
  final String url;

  /// The same address with the `webcal` scheme, which opens Apple Calendar's
  /// subscription dialog.
  final String webcalUrl;
  final DateTime createdAt;

  Map<String, dynamic> toJson() => {
    'url': url,
    'webcalUrl': webcalUrl,
    'createdAt': createdAt.toUtc().toIso8601String(),
  };

  /// The https address limited to [scope].
  String httpsFor(CalendarFeedScope scope) => _withRole(url, scope);

  /// The webcal address limited to [scope].
  String webcalFor(CalendarFeedScope scope) => _withRole(webcalUrl, scope);

  /// Opens Google Calendar's "add calendar" page with the feed filled in.
  Uri googleFor(CalendarFeedScope scope) => Uri.https(
    'calendar.google.com',
    '/calendar/render',
    {'cid': httpsFor(scope)},
  );

  static String _withRole(String address, CalendarFeedScope scope) =>
      scope.role == null ? address : '$address?role=${scope.role}';
}

class CalendarFeedApi {
  const CalendarFeedApi(this._dio);

  final Dio _dio;

  Future<CalendarFeedStatus> fetchStatus() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ownCalendarFeedPath,
      );
      return CalendarFeedStatus.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// Creates a new link; the previous one stops working.
  Future<CalendarFeedLink> create() async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ownCalendarFeedPath,
      );
      return CalendarFeedLink.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// The link stops working; subscribed calendars get no more updates.
  Future<void> turnOff() async {
    try {
      await _dio.delete<void>(ownCalendarFeedPath);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final calendarFeedApiProvider = Provider<CalendarFeedApi>(
  (ref) => CalendarFeedApi(ref.watch(dioProvider)),
);

final calendarFeedStorageProvider = Provider<FlutterSecureStorage>(
  (ref) => const FlutterSecureStorage(),
);

/// What the calendar sync screen shows: whether the account has a working
/// link and, when it was created on this device, the link itself.
class CalendarFeedState {
  const CalendarFeedState({required this.active, this.link});

  final bool active;

  /// The working link, or `null` when there is none or it was created on
  /// another device (the backend keeps only a hash of its token).
  final CalendarFeedLink? link;
}

/// The account's calendar feed link, kept in secure storage per account so
/// one account's link is never shown to another account on the same phone.
class CalendarFeedController extends AsyncNotifier<CalendarFeedState> {
  CalendarFeedController(this.accountId);

  final String accountId;

  String get _storageKey => 'calendar_feed_link.$accountId';

  @override
  Future<CalendarFeedState> build() async {
    final status = await ref.watch(calendarFeedApiProvider).fetchStatus();
    final stored = await _read();
    if (!status.active) {
      if (stored != null) await _delete();
      return const CalendarFeedState(active: false);
    }
    // A link stored here works only while it is the account's current link.
    final current =
        stored != null &&
        status.createdAt != null &&
        stored.createdAt.isAtSameMomentAs(status.createdAt!);
    return CalendarFeedState(active: true, link: current ? stored : null);
  }

  /// Creates a new link and keeps it on this device.
  Future<void> create() async {
    final link = await ref.read(calendarFeedApiProvider).create();
    await _write(link);
    state = AsyncData(CalendarFeedState(active: true, link: link));
  }

  Future<void> turnOff() async {
    await ref.read(calendarFeedApiProvider).turnOff();
    await _delete();
    state = const AsyncData(CalendarFeedState(active: false));
  }

  Future<CalendarFeedLink?> _read() async {
    try {
      final value = await ref
          .read(calendarFeedStorageProvider)
          .read(key: _storageKey);
      return value == null
          ? null
          : CalendarFeedLink.fromJson(
              jsonDecode(value) as Map<String, dynamic>,
            );
    } catch (_) {
      // An unreadable link is treated as created on another device.
      return null;
    }
  }

  Future<void> _write(CalendarFeedLink link) async {
    try {
      await ref
          .read(calendarFeedStorageProvider)
          .write(key: _storageKey, value: jsonEncode(link.toJson()));
    } catch (_) {
      // The link still works; the screen offers a new one after a restart.
    }
  }

  Future<void> _delete() async {
    try {
      await ref.read(calendarFeedStorageProvider).delete(key: _storageKey);
    } catch (_) {
      // Nothing to keep: the link no longer works.
    }
  }
}

final calendarFeedProvider = AsyncNotifierProvider.autoDispose
    .family<CalendarFeedController, CalendarFeedState, String>(
      CalendarFeedController.new,
    );
