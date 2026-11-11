import 'package:flutter/material.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Booking rows retain the shared calendar's Card, stripe and typography.
class ClientScheduleEventTile extends StatelessWidget {
  const ClientScheduleEventTile({
    required this.event,
    required this.onTap,
    this.participant,
    super.key,
  });
  final GroupClass event;
  final VoidCallback onTap;
  final Widget? participant;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final cancelled = event.status == 'CANCELLED';
    final color = cancelled
        ? theme.colorScheme.error
        : classServiceColor(event, theme.colorScheme);
    final teachers = event.staff
        .where((person) => person.isLead && person.displayName != null)
        .map((person) => person.displayName!)
        .join(', ');
    return ClientScheduleCard(
      color: color,
      time: classTime(event),
      title: event.name ?? l10n.classesTitle,
      trainer: teachers.isEmpty ? l10n.classesInstructorPending : teachers,
      room: event.roomNames.isEmpty
          ? l10n.classesRoomPending
          : event.roomNames.join(', '),
      cancelled: cancelled,
      participant: participant,
      onTap: onTap,
      footer: [
        if (event.isReserved)
          ScheduleEventMarker(Icons.check_circle_outline, l10n.classesReserved)
        else if (classWaitingEntry(event) case final entry?)
          ScheduleEventMarker(
            Icons.hourglass_top_outlined,
            classWaitlistMarker(l10n, entry),
          ),
        if (cancelled)
          Text(l10n.eventStatusCancelled, style: TextStyle(color: color))
        else
          ClassOccupancy(event),
      ],
    );
  }
}

/// A shared layout for the client's group classes and personal trainings.
class ClientScheduleCard extends StatelessWidget {
  const ClientScheduleCard({
    required this.color,
    required this.time,
    required this.title,
    required this.trainer,
    required this.room,
    required this.onTap,
    required this.footer,
    this.participant,
    this.cancelled = false,
    super.key,
  });

  final Color color;
  final String time;
  final String title;
  final String trainer;
  final String room;
  final VoidCallback onTap;
  final List<Widget> footer;
  final Widget? participant;
  final bool cancelled;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(width: 6, color: color),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 6,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              time,
                              style: theme.textTheme.labelLarge,
                            ),
                          ),
                          if (participant != null) ...[
                            Flexible(child: participant!),
                            const SizedBox(width: 8),
                          ],
                          const Icon(Icons.chevron_right, size: 20),
                        ],
                      ),
                      Text(
                        title,
                        style: theme.textTheme.titleMedium?.copyWith(
                          decoration: cancelled
                              ? TextDecoration.lineThrough
                              : null,
                          color: cancelled ? theme.colorScheme.error : null,
                        ),
                      ),
                      Text(trainer, style: theme.textTheme.bodySmall),
                      Text(room, style: theme.textTheme.bodySmall),
                      ...footer,
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The participant's own state on the class: a reservation or a waiting place.
class ScheduleEventMarker extends StatelessWidget {
  const ScheduleEventMarker(this.icon, this.text, {super.key, this.color});
  final Color? color;
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Icon(icon, size: 16, color: color ?? theme.colorScheme.primary),
        const SizedBox(width: 6),
        Flexible(child: Text(text, style: theme.textTheme.labelMedium)),
      ],
    );
  }
}

class ClientScheduleLegend extends StatelessWidget {
  const ClientScheduleLegend({required this.events, super.key});
  final List<GroupClass> events;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final services = <String, GroupClass>{};
    for (final event in events) {
      services.putIfAbsent(
        event.serviceTypeId ?? event.serviceTypeName ?? '',
        () => event,
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Wrap(
        spacing: 12,
        runSpacing: 8,
        children: [
          for (final event in services.values)
            _entry(
              context,
              classServiceColor(event, theme.colorScheme),
              event.serviceTypeName ?? l10n.classesTitle,
            ),
          if (events.any((event) => event.status == 'CANCELLED'))
            _entry(context, theme.colorScheme.error, l10n.eventStatusCancelled),
        ],
      ),
    );
  }

  Widget _entry(BuildContext context, Color color, String label) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
      const SizedBox(width: 5),
      Flexible(
        child: Text(label, style: Theme.of(context).textTheme.bodySmall),
      ),
    ],
  );
}
