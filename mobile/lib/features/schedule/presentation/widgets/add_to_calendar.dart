import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/schedule/data/device_calendar.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/widgets/schedule_calendar.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Whether a single event can be added to the phone's calendar: a cancelled
/// one would only mislead.
bool canAddToDeviceCalendar(ScheduleEvent event) =>
    event.status != EventStatus.cancelled;

/// The phone's calendar entry for [event]. A guardian sees whose event it is,
/// as in notifications: "Zosia: Pole dance".
DeviceCalendarEvent deviceCalendarEvent(
  AppLocalizations l10n,
  ScheduleEvent event, {
  String? participant,
}) {
  final name = event.name ?? eventKindLabel(l10n, event.kind);
  final leads = [
    for (final person in event.staff)
      if (person.isLead && person.displayName != null) person.displayName!,
  ];
  return DeviceCalendarEvent(
    title: participant == null ? name : '$participant: $name',
    startsAt: event.startsAt,
    endsAt: event.endsAt,
    location: event.roomNames.isEmpty ? null : event.roomNames.join(', '),
    description: leads.isEmpty
        ? null
        : l10n.eventCalendarLeads(leads.join(', ')),
  );
}

/// Opens the phone's calendar app with [event] filled in.
Future<void> addToDeviceCalendar(
  BuildContext context,
  WidgetRef ref,
  ScheduleEvent event, {
  String? participant,
}) async {
  final l10n = AppLocalizations.of(context);
  final messenger = ScaffoldMessenger.of(context);
  var opened = false;
  try {
    opened = await ref
        .read(deviceCalendarProvider)
        .add(deviceCalendarEvent(l10n, event, participant: participant));
  } catch (_) {
    opened = false;
  }
  if (!opened) {
    messenger.showSnackBar(
      SnackBar(content: Text(l10n.eventAddToCalendarFailed)),
    );
  }
}

/// Shown when an event in the schedule is held down: the event and the
/// action that adds it to the phone's calendar.
Future<void> showAddToCalendarSheet(
  BuildContext context,
  WidgetRef ref,
  ScheduleEvent event, {
  String? participant,
}) {
  final l10n = AppLocalizations.of(context);
  final name = event.name ?? eventKindLabel(l10n, event.kind);
  final time =
      '${DateFormat('dd.MM.yyyy, HH:mm', 'pl').format(event.startsAt)}–'
      '${DateFormat('HH:mm').format(event.endsAt)}';
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (sheetContext) => SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ListTile(
            title: Text(participant == null ? name : '$participant: $name'),
            subtitle: Text(time),
          ),
          const Divider(height: 1),
          ListTile(
            key: const Key('addEventToCalendar'),
            leading: const Icon(Icons.edit_calendar_outlined),
            title: Text(l10n.eventAddToCalendar),
            subtitle: Text(l10n.eventAddToCalendarHint),
            onTap: () {
              Navigator.of(sheetContext).pop();
              addToDeviceCalendar(
                context,
                ref,
                event,
                participant: participant,
              );
            },
          ),
        ],
      ),
    ),
  );
}

/// An event row of the schedule that offers adding the event to the phone's
/// calendar when it is held down. Taps still open the event.
class AddToCalendarLongPress extends ConsumerWidget {
  const AddToCalendarLongPress({
    required this.event,
    required this.child,
    this.participant,
    super.key,
  });

  final ScheduleEvent event;
  final String? participant;
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!canAddToDeviceCalendar(event)) return child;
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onLongPress: () =>
          showAddToCalendarSheet(context, ref, event, participant: participant),
      child: child,
    );
  }
}

/// The button on an event's details that adds it to the phone's calendar.
class AddToCalendarButton extends ConsumerWidget {
  const AddToCalendarButton({required this.event, this.participant, super.key});

  final ScheduleEvent event;
  final String? participant;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!canAddToDeviceCalendar(event)) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.centerLeft,
      child: OutlinedButton.icon(
        key: const Key('addToCalendarButton'),
        onPressed: () =>
            addToDeviceCalendar(context, ref, event, participant: participant),
        icon: const Icon(Icons.edit_calendar_outlined),
        label: Text(AppLocalizations.of(context).eventAddToCalendar),
      ),
    );
  }
}
