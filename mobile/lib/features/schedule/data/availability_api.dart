import 'package:dio/dio.dart';
import 'package:flutter/material.dart' show TimeOfDay;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

const myAvailabilityPath = '/api/v1/me/availability';

/// Hours in which the trainer can run classes, in the studio's local time:
/// either repeating every week or on a single extra day.
class AvailabilitySlot {
  const AvailabilitySlot({
    required this.id,
    required this.weekday,
    required this.date,
    required this.start,
    required this.end,
    required this.validFrom,
    required this.validTo,
  });

  factory AvailabilitySlot.fromJson(Map<String, dynamic> json) {
    return AvailabilitySlot(
      id: json['id'] as String,
      weekday: json['weekday'] as int,
      date: _parseDate(json['date'] as String?),
      start: parseTime(json['startTime'] as String),
      end: parseTime(json['endTime'] as String),
      validFrom: _parseDate(json['validFrom'] as String?)!,
      validTo: _parseDate(json['validTo'] as String?),
    );
  }

  final String id;

  /// ISO weekday, 1 = Monday.
  final int weekday;

  /// The day of a single extra day; `null` for weekly hours.
  final DateTime? date;
  final TimeOfDay start;
  final TimeOfDay end;
  final DateTime validFrom;

  /// The last day the hours apply, or `null` with no end.
  final DateTime? validTo;

  bool get isWeekly => date == null;
}

TimeOfDay parseTime(String value) {
  final parts = value.split(':');
  return TimeOfDay(hour: int.parse(parts[0]), minute: int.parse(parts[1]));
}

String formatTime(TimeOfDay time) =>
    '${time.hour.toString().padLeft(2, '0')}:'
    '${time.minute.toString().padLeft(2, '0')}';

String formatDate(DateTime date) =>
    '${date.year.toString().padLeft(4, '0')}-'
    '${date.month.toString().padLeft(2, '0')}-'
    '${date.day.toString().padLeft(2, '0')}';

DateTime? _parseDate(String? value) {
  if (value == null) {
    return null;
  }
  final parts = value.split('-').map(int.parse).toList();
  return DateTime(parts[0], parts[1], parts[2]);
}

class AvailabilityApi {
  const AvailabilityApi(this._dio);

  final Dio _dio;

  /// Weekly hours by weekday, then single days by date.
  Future<List<AvailabilitySlot>> fetchSlots() {
    return _call(() async {
      final response = await _dio.get<Map<String, dynamic>>(myAvailabilityPath);
      return [
        for (final item in response.data!['items'] as List)
          AvailabilitySlot.fromJson(item as Map<String, dynamic>),
      ];
    });
  }

  Future<void> addWeekly({
    required int weekday,
    required TimeOfDay start,
    required TimeOfDay end,
    DateTime? validTo,
  }) {
    return _call(
      () => _dio.post<Map<String, dynamic>>(
        myAvailabilityPath,
        data: {
          'weekday': weekday,
          'startTime': formatTime(start),
          'endTime': formatTime(end),
          'validTo': validTo == null ? null : formatDate(validTo),
        },
      ),
    );
  }

  Future<void> addDay({
    required DateTime date,
    required TimeOfDay start,
    required TimeOfDay end,
  }) {
    return _call(
      () => _dio.post<Map<String, dynamic>>(
        myAvailabilityPath,
        data: {
          'date': formatDate(date),
          'startTime': formatTime(start),
          'endTime': formatTime(end),
        },
      ),
    );
  }

  /// Sends new times; for weekly hours also the end date, where `null`
  /// removes it.
  Future<void> update(
    AvailabilitySlot slot, {
    required TimeOfDay start,
    required TimeOfDay end,
    DateTime? validTo,
  }) {
    return _call(
      () => _dio.patch<Map<String, dynamic>>(
        '$myAvailabilityPath/${slot.id}',
        data: {
          'startTime': formatTime(start),
          'endTime': formatTime(end),
          if (slot.isWeekly)
            'validTo': validTo == null ? null : formatDate(validTo),
        },
      ),
    );
  }

  Future<void> remove(AvailabilitySlot slot) {
    return _call(() => _dio.delete<void>('$myAvailabilityPath/${slot.id}'));
  }

  static Future<T> _call<T>(Future<T> Function() request) async {
    try {
      return await request();
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final availabilityApiProvider = Provider<AvailabilityApi>(
  (ref) => AvailabilityApi(ref.watch(dioProvider)),
);

/// The signed-in trainer's availability. Every change reloads the list, so
/// the server's order and any slot it split stay authoritative.
class AvailabilityController extends AsyncNotifier<List<AvailabilitySlot>> {
  AvailabilityController(this.accountId);

  // Account scoping prevents another signed-in account from reusing this data.
  final String accountId;

  @override
  Future<List<AvailabilitySlot>> build() {
    return ref.watch(availabilityApiProvider).fetchSlots();
  }

  /// Runs a change and then reloads the list. Throws the change's error and
  /// leaves the shown list as it was.
  Future<void> change(Future<void> Function(AvailabilityApi api) action) async {
    final api = ref.read(availabilityApiProvider);
    await action(api);
    final slots = await api.fetchSlots();
    if (ref.mounted) {
      state = AsyncData(slots);
    }
  }
}

final availabilityProvider = AsyncNotifierProvider.autoDispose
    .family<AvailabilityController, List<AvailabilitySlot>, String>(
      AvailabilityController.new,
      retry: (_, _) => null,
    );
