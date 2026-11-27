import 'package:device_calendar_plus/device_calendar_plus.dart' as plugin;
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A calendar on the phone that events can be written to, e.g. the user's
/// Google calendar, which the phone sends to Google Calendar at once.
class PhoneCalendarInfo {
  const PhoneCalendarInfo({
    required this.id,
    required this.name,
    this.accountName,
    this.isPrimary = false,
  });

  final String id;
  final String name;

  /// The account the calendar belongs to, e.g. a Google e-mail address.
  final String? accountName;
  final bool isPrimary;
}

/// An event as written to a calendar on the phone.
class PhoneEventData {
  const PhoneEventData({
    required this.title,
    required this.startsAt,
    required this.endsAt,
    this.location,
    this.description,
    this.busy = true,
  });

  final String title;
  final DateTime startsAt;
  final DateTime endsAt;
  final String? location;
  final String? description;

  /// Whether the event marks the time as busy; a cancelled one does not.
  final bool busy;
}

/// The phone's calendars (Android's Calendar Provider, iOS's EventKit),
/// through `device_calendar_plus`. Replaced in tests.
class PhoneCalendar {
  const PhoneCalendar();

  plugin.DeviceCalendar get _calendar => plugin.DeviceCalendar.instance;

  /// Asks for full calendar access when needed; whether it was granted.
  Future<bool> requestAccess() async {
    final status = await _calendar.requestPermissions();
    return status == plugin.CalendarPermissionStatus.granted;
  }

  /// Opens the app's settings, where access refused earlier can be granted.
  Future<void> openSettings() => _calendar.openAppSettings();

  /// The calendars events can be written to, the primary ones first.
  Future<List<PhoneCalendarInfo>> writableCalendars() async {
    final calendars = await _calendar.listCalendars();
    return [
      for (final calendar in calendars)
        if (!calendar.readOnly && !calendar.hidden)
          PhoneCalendarInfo(
            id: calendar.id,
            name: calendar.name,
            accountName: calendar.accountName,
            isPrimary: calendar.isPrimary,
          ),
    ]..sort((a, b) => (b.isPrimary ? 1 : 0) - (a.isPrimary ? 1 : 0));
  }

  /// Whether the calendar still has the event; the user may have deleted it.
  Future<bool> exists(String eventId) async =>
      await _calendar.getEvent(eventId) != null;

  /// Writes a new event and returns its id.
  Future<String> create(String calendarId, PhoneEventData event) =>
      _calendar.createEvent(
        calendarId: calendarId,
        title: event.title,
        startDate: event.startsAt,
        endDate: event.endsAt,
        location: event.location,
        description: event.description,
        availability: _availability(event),
      );

  Future<void> update(String eventId, PhoneEventData event) =>
      _calendar.updateEvent(
        instanceId: eventId,
        title: event.title,
        startDate: event.startsAt,
        endDate: event.endsAt,
        location: _patch(event.location),
        description: _patch(event.description),
        availability: _availability(event),
      );

  /// Removes the event; does nothing when it no longer exists.
  Future<void> delete(String eventId) async {
    try {
      await _calendar.deleteEvent(instanceId: eventId);
    } on plugin.DeviceCalendarException catch (e) {
      if (e.errorCode != plugin.DeviceCalendarError.notFound) rethrow;
    }
  }

  static plugin.EventAvailability _availability(PhoneEventData event) =>
      event.busy
      ? plugin.EventAvailability.busy
      : plugin.EventAvailability.free;

  static plugin.Patch<String> _patch(String? value) =>
      value == null ? const plugin.Patch.clear() : plugin.Patch.set(value);
}

final phoneCalendarProvider = Provider<PhoneCalendar>(
  (ref) => const PhoneCalendar(),
);
