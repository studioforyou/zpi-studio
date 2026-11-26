import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/schedule/data/calendar_events_api.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';
import 'package:mobile/features/schedule/data/phone_calendar.dart';

/// An event this app wrote to a calendar on the phone.
class SyncedEvent {
  const SyncedEvent({
    required this.eventId,
    required this.fingerprint,
    required this.startsAt,
  });

  factory SyncedEvent.fromJson(Map<String, dynamic> json) => SyncedEvent(
    eventId: json['eventId'] as String,
    fingerprint: json['fingerprint'] as String,
    startsAt: DateTime.parse(json['startsAt'] as String),
  );

  /// The event's id in the phone's calendar.
  final String eventId;

  /// What was written, to tell whether the event changed in the studio since.
  final String fingerprint;
  final DateTime startsAt;

  Map<String, dynamic> toJson() => {
    'eventId': eventId,
    'fingerprint': fingerprint,
    'startsAt': startsAt.toUtc().toIso8601String(),
  };
}

/// Where and what "Synchronizuj teraz" last wrote on this phone.
class CalendarSyncRecord {
  const CalendarSyncRecord({
    required this.calendarId,
    required this.calendarName,
    required this.scope,
    required this.syncedAt,
    required this.events,
  });

  factory CalendarSyncRecord.fromJson(Map<String, dynamic> json) =>
      CalendarSyncRecord(
        calendarId: json['calendarId'] as String,
        calendarName: json['calendarName'] as String,
        scope: CalendarFeedScope.values.byName(json['scope'] as String),
        syncedAt: DateTime.parse(json['syncedAt'] as String),
        events: {
          for (final MapEntry(:key, :value)
              in (json['events'] as Map<String, dynamic>).entries)
            key: SyncedEvent.fromJson(value as Map<String, dynamic>),
        },
      );

  final String calendarId;
  final String calendarName;
  final CalendarFeedScope scope;
  final DateTime syncedAt;

  /// The written events by their entry's uid.
  final Map<String, SyncedEvent> events;

  Map<String, dynamic> toJson() => {
    'calendarId': calendarId,
    'calendarName': calendarName,
    'scope': scope.name,
    'syncedAt': syncedAt.toUtc().toIso8601String(),
    'events': {
      for (final MapEntry(:key, :value) in events.entries) key: value.toJson(),
    },
  };
}

/// How many events one sync added, updated and removed.
class CalendarSyncResult {
  const CalendarSyncResult({
    this.added = 0,
    this.updated = 0,
    this.removed = 0,
  });

  final int added;
  final int updated;
  final int removed;

  bool get unchanged => added == 0 && updated == 0 && removed == 0;
}

/// Brings a calendar on the phone up to date with the account's schedule.
///
/// - An event that is not in the calendar yet (or that the user deleted
///   there) is added, unless it has already ended or the studio cancelled it.
/// - An event that changed in the studio since the last sync is updated. One
///   the studio cancelled stays, titled as cancelled and not marking the time
///   as busy, like `STATUS:CANCELLED` in the calendar feed.
/// - An upcoming event that is no longer in the schedule, e.g. a reservation
///   the client cancelled, is removed. Past ones stay in the calendar.
///
/// Only events this app wrote are ever changed, so nothing else in the
/// calendar is touched, and syncing again never adds duplicates. [run]
/// records every write in `events` as it happens, so a sync that fails half
/// way can be saved and continued.
class CalendarSyncer {
  const CalendarSyncer(this._phone);

  final PhoneCalendar _phone;

  Future<CalendarSyncResult> run({
    required String calendarId,
    required List<CalendarEntry> entries,
    required Map<String, SyncedEvent> events,
    required String cancelledPrefix,
    required DateTime now,
  }) async {
    final seen = <String>{};
    var added = 0;
    var updated = 0;
    var removed = 0;
    for (final entry in entries) {
      seen.add(entry.uid);
      final data = phoneEvent(entry, cancelledPrefix);
      final fingerprint = _fingerprint(data);
      final previous = events[entry.uid];
      if (previous != null && await _phone.exists(previous.eventId)) {
        if (previous.fingerprint != fingerprint) {
          await _phone.update(previous.eventId, data);
          updated++;
        }
        events[entry.uid] = SyncedEvent(
          eventId: previous.eventId,
          fingerprint: fingerprint,
          startsAt: entry.startsAt,
        );
      } else if (entry.cancelled || !entry.endsAt.isAfter(now)) {
        events.remove(entry.uid);
      } else {
        final eventId = await _phone.create(calendarId, data);
        added++;
        events[entry.uid] = SyncedEvent(
          eventId: eventId,
          fingerprint: fingerprint,
          startsAt: entry.startsAt,
        );
      }
    }
    for (final uid in events.keys.toList()) {
      if (seen.contains(uid)) continue;
      final event = events[uid]!;
      if (event.startsAt.isAfter(now)) {
        await _phone.delete(event.eventId);
        removed++;
      }
      events.remove(uid);
    }
    return CalendarSyncResult(added: added, updated: updated, removed: removed);
  }

  /// Removes the upcoming events this app wrote; past ones stay.
  Future<int> removeUpcoming(
    Map<String, SyncedEvent> synced,
    DateTime now,
  ) async {
    var removed = 0;
    for (final event in synced.values) {
      if (!event.startsAt.isAfter(now)) continue;
      await _phone.delete(event.eventId);
      removed++;
    }
    return removed;
  }

  static PhoneEventData phoneEvent(
    CalendarEntry entry,
    String cancelledPrefix,
  ) => PhoneEventData(
    title: entry.cancelled ? '$cancelledPrefix${entry.title}' : entry.title,
    startsAt: entry.startsAt,
    endsAt: entry.endsAt,
    location: entry.location,
    description: entry.description,
    busy: !entry.cancelled,
  );

  static String _fingerprint(PhoneEventData data) => [
    data.title,
    data.startsAt.toUtc().toIso8601String(),
    data.endsAt.toUtc().toIso8601String(),
    data.location ?? '',
    data.description ?? '',
    data.busy,
  ].join('\u001f');
}

/// Clock of the sync; replaced in tests.
final calendarSyncClockProvider = Provider<DateTime Function()>(
  (ref) => DateTime.now,
);

/// "Synchronizuj teraz": writes the account's schedule to a calendar on the
/// phone right away, instead of waiting for a subscribed calendar to download
/// the feed again. What was written is kept in secure storage per account, so
/// the next sync updates those events instead of adding them again.
class CalendarSyncNowController extends AsyncNotifier<CalendarSyncRecord?> {
  CalendarSyncNowController(this.accountId);

  final String accountId;

  String get _storageKey => 'calendar_sync_now.$accountId';

  CalendarSyncer get _syncer => CalendarSyncer(ref.read(phoneCalendarProvider));

  @override
  Future<CalendarSyncRecord?> build() async {
    try {
      final value = await ref
          .read(calendarFeedStorageProvider)
          .read(key: _storageKey);
      return value == null
          ? null
          : CalendarSyncRecord.fromJson(
              jsonDecode(value) as Map<String, dynamic>,
            );
    } catch (_) {
      // Unreadable: the next sync asks for a calendar again.
      return null;
    }
  }

  /// Syncs to [calendar] with [scope], or to the calendar and scope of the
  /// last sync when they are `null`. Moving to another calendar first removes
  /// the upcoming events from the previous one.
  Future<CalendarSyncResult> sync({
    required String cancelledPrefix,
    PhoneCalendarInfo? calendar,
    CalendarFeedScope? scope,
  }) async {
    final previous = await future;
    final calendarId = calendar?.id ?? previous?.calendarId;
    final calendarName = calendar?.name ?? previous?.calendarName;
    if (calendarId == null || calendarName == null) {
      throw StateError('No calendar chosen.');
    }
    final now = ref.read(calendarSyncClockProvider)();
    final movesCalendar = previous != null && previous.calendarId != calendarId;
    if (movesCalendar) {
      await _syncer.removeUpcoming(previous.events, now);
    }
    final chosenScope = scope ?? previous?.scope ?? CalendarFeedScope.all;
    final entries = await ref
        .read(calendarEventsApiProvider)
        .fetch(chosenScope);
    final events = previous == null || movesCalendar
        ? <String, SyncedEvent>{}
        : Map.of(previous.events);
    CalendarSyncRecord record(DateTime syncedAt) => CalendarSyncRecord(
      calendarId: calendarId,
      calendarName: calendarName,
      scope: chosenScope,
      syncedAt: syncedAt,
      events: events,
    );
    try {
      final result = await _syncer.run(
        calendarId: calendarId,
        entries: entries,
        events: events,
        cancelledPrefix: cancelledPrefix,
        now: now,
      );
      await _write(record(now));
      state = AsyncData(record(now));
      return result;
    } catch (_) {
      // Keep what was written, so the next sync does not add it again.
      final partial = record(previous?.syncedAt ?? now);
      await _write(partial);
      state = AsyncData(partial);
      rethrow;
    }
  }

  /// Removes the upcoming events this app wrote and forgets the calendar.
  Future<int> removeAll() async {
    final previous = await future;
    if (previous == null) return 0;
    final removed = await _syncer.removeUpcoming(
      previous.events,
      ref.read(calendarSyncClockProvider)(),
    );
    try {
      await ref.read(calendarFeedStorageProvider).delete(key: _storageKey);
    } catch (_) {
      // The events are gone; a stale record only offers them again.
    }
    state = const AsyncData(null);
    return removed;
  }

  Future<void> _write(CalendarSyncRecord record) async {
    try {
      await ref
          .read(calendarFeedStorageProvider)
          .write(key: _storageKey, value: jsonEncode(record.toJson()));
    } catch (_) {
      // The events are written, but the next sync will add them again.
    }
  }
}

final calendarSyncNowProvider = AsyncNotifierProvider.autoDispose
    .family<CalendarSyncNowController, CalendarSyncRecord?, String>(
      CalendarSyncNowController.new,
    );
