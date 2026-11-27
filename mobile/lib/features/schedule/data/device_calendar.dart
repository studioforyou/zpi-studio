import 'package:add_2_calendar/add_2_calendar.dart' as add2cal;
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One event to add to the phone's calendar.
class DeviceCalendarEvent {
  const DeviceCalendarEvent({
    required this.title,
    required this.startsAt,
    required this.endsAt,
    this.location,
    this.description,
  });

  final String title;
  final DateTime startsAt;
  final DateTime endsAt;
  final String? location;
  final String? description;
}

/// Adds single events to the phone's calendar. The phone's calendar app opens
/// with the event filled in, and the user saves it there, in any of their
/// calendars (e.g. Google or iCloud). It is a copy: later changes in the
/// studio do not reach it; the calendar feed keeps a calendar up to date.
class DeviceCalendar {
  const DeviceCalendar();

  /// Whether the calendar app was opened.
  Future<bool> add(DeviceCalendarEvent event) =>
      add2cal.Add2Calendar.addEvent2Cal(
        add2cal.Event(
          title: event.title,
          startDate: event.startsAt,
          endDate: event.endsAt,
          location: event.location,
          description: event.description,
        ),
      );
}

final deviceCalendarProvider = Provider<DeviceCalendar>(
  (ref) => const DeviceCalendar(),
);
